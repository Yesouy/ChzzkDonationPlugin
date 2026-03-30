package com.example.cheezedonation;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

// HttpServer 관련 import
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

// Socket.IO 관련 import
import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import java.nio.charset.StandardCharsets;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

/**
 * 치지직 OAuth + Socket.IO 실시간 후원 시스템 관리자 (개선된 버전)
 */
public class cheezeManager implements Listener {

    private final JavaPlugin plugin;
    private final cheeze_donation_plugin donationPlugin;
    private final Gson gson;

    private BukkitRunnable tokenMaintenanceTask;

    // 설정 정보 동적 기능
    private final String clientId;
    private final String clientSecret;
    private final String redirectUri; // 동적 설정 가능
    private final String authBaseUrl = "https://openapi.chzzk.naver.com";
    private final String apiBaseUrl = "https://openapi.chzzk.naver.com";
    private final int callbackPort; // 포트도 설정 가능하게

    // OAuth 상태 관리
    private final Map<String, String> pendingAuth = new ConcurrentHashMap<>();
    private final Map<String, UserAuth> userTokens = new ConcurrentHashMap<>();
    private final Map<String, Socket> activeSockets = new ConcurrentHashMap<>();
    private final Map<String, String> sessionKeys = new ConcurrentHashMap<>();
    private final Set<String> processedDonations = new HashSet<>();

    // 로컬 콜백 서버
    private HttpServer callbackServer;

    // 재시도 설정
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 2000;

    /**
     * 사용자 인증 정보 클래스
     */
    public static class UserAuth {
        public final String accessToken;
        public final String refreshToken;
        public final String playerName;
        public final String sessionUrl;

        public UserAuth(String accessToken, String refreshToken, String playerName, String sessionUrl) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.playerName = playerName;
            this.sessionUrl = sessionUrl;
        }
    }

    public cheezeManager(JavaPlugin plugin, cheeze_donation_plugin donationPlugin) {
        this.plugin = plugin;
        this.donationPlugin = donationPlugin;
        this.gson = new Gson();

        // config.yml에서 설정 로드
        this.clientId = plugin.getConfig().getString("chzzk.client-id", "");
        this.clientSecret = plugin.getConfig().getString("chzzk.client-secret", "");

        // 콜백 URL 동적 설정
        this.callbackPort = plugin.getConfig().getInt("chzzk.callback.port", 8080);
        this.redirectUri = buildCallbackUrl();

        // 이벤트 리스너 등록
        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        // 저장된 토큰 로드
        loadUserTokens();
    }

    /**
     * 콜백 URL 동적 생성
     */
    private String buildCallbackUrl() {
        boolean autoDetect = plugin.getConfig().getBoolean("chzzk.callback.auto-detect", false);
        String externalUrl = plugin.getConfig().getString("chzzk.callback.external-url", "");
        boolean useSSL = plugin.getConfig().getBoolean("chzzk.callback.use-ssl", false);

        String protocol = useSSL ? "https" : "http";
        String baseUrl;

        if (autoDetect) {
            // 자동 IP 감지
            baseUrl = detectPublicIP();
            if (baseUrl == null) {
                plugin.getLogger().warning("⚠️ 공인 IP 자동 감지 실패, localhost 사용");
                baseUrl = "localhost";
            }
        } else if (!externalUrl.isEmpty()) {
            // 설정된 외부 URL 사용
            baseUrl = externalUrl.replaceFirst("^https?://", ""); // 프로토콜 제거
        } else {
            // 기본값: localhost
            baseUrl = "localhost";
            plugin.getLogger().warning("⚠️ 외부 URL이 설정되지 않음. localhost 사용 (로컬 테스트만 가능)");
        }

        String fullUrl = protocol + "://" + baseUrl + ":" + callbackPort + "/callback";
        plugin.getLogger().info("🔗 콜백 URL: " + fullUrl);

        return fullUrl;
    }

    /**
     * 공인 IP 자동 감지 (여러 서비스 시도)
     */
    private String detectPublicIP() {
        String[] ipServices = {
                "https://api.ipify.org",
                "https://checkip.amazonaws.com",
                "https://icanhazip.com",
                "https://ident.me"
        };

        for (String service : ipServices) {
            try {
                URL url = new URL(service);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);

                if (conn.getResponseCode() == 200) {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(conn.getInputStream()))) {
                        String ip = reader.readLine().trim();
                        if (isValidIP(ip)) {
                            plugin.getLogger().info("🌐 자동 감지된 공인 IP: " + ip);
                            return ip;
                        }
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warning("IP 감지 서비스 실패: " + service + " - " + e.getMessage());
            }
        }
        return null;
    }

    /**
     * IP 주소 유효성 검사
     */
    private boolean isValidIP(String ip) {
        if (ip == null || ip.isEmpty()) return false;
        String[] parts = ip.split("\\.");
        if (parts.length != 4) return false;

        try {
            for (String part : parts) {
                int num = Integer.parseInt(part);
                if (num < 0 || num > 255) return false;
            }
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 콜백 서버 시작 (포트 동적 설정)
     */
    private void startCallbackServer() throws IOException {
        try {
            callbackServer = HttpServer.create(new InetSocketAddress(callbackPort), 0);

            // OAuth 콜백 핸들러
            callbackServer.createContext("/callback", new HttpHandler() {
                @Override
                public void handle(HttpExchange exchange) throws IOException {
                    handleCallback(exchange);
                }
            });

            // 디버그 페이지 핸들러
            callbackServer.createContext("/debug", new HttpHandler() {
                @Override
                public void handle(HttpExchange exchange) throws IOException {
                    handleDebug(exchange);
                }
            });

            // 상태 확인 페이지 (새로 추가)
            callbackServer.createContext("/status", new HttpHandler() {
                @Override
                public void handle(HttpExchange exchange) throws IOException {
                    handleStatus(exchange);
                }
            });

            callbackServer.start();
            plugin.getLogger().info("✅ 콜백 서버 시작 성공 (포트: " + callbackPort + ")");
            plugin.getLogger().info("🔗 콜백 URL: " + redirectUri);
            plugin.getLogger().info("🔧 디버그 페이지: " + redirectUri.replace("/callback", "/debug"));
            plugin.getLogger().info("📊 상태 페이지: " + redirectUri.replace("/callback", "/status"));

            // 포트포워딩 안내
            if (redirectUri.contains("localhost")) {
                plugin.getLogger().warning("⚠️ localhost 사용 중 - 외부 접근 불가!");
                plugin.getLogger().warning("📝 다른 컴퓨터에서 접근하려면:");
                plugin.getLogger().warning("   1. 라우터에서 포트 " + callbackPort + " 포트포워딩 설정");
                plugin.getLogger().warning("   2. config.yml에서 external-url 설정");
                plugin.getLogger().warning("   3. 치지직 개발자 센터에서 리디렉션 URL 변경");
            }

        } catch (IOException e) {
            plugin.getLogger().severe("❌ 콜백 서버 시작 실패: " + e.getMessage());
            plugin.getLogger().severe("💡 포트 " + callbackPort + "이(가) 이미 사용 중일 수 있습니다.");
            plugin.getLogger().severe("💡 config.yml에서 다른 포트를 설정해보세요.");
            throw e;
        }
    }

    /**
     * 상태 확인 페이지 핸들러 (새로 추가)
     */
    private void handleStatus(HttpExchange exchange) throws IOException {
        String statusInfo = "<html><body style='font-family: Arial, sans-serif; padding: 20px;'>" +
                "<h2>🎮 치지직 OAuth 서버 상태</h2>" +
                "<div style='background: #f0f0f0; padding: 15px; border-radius: 5px; margin: 10px 0;'>" +
                "<p><strong>서버 상태:</strong> <span style='color: green;'>✅ 정상 작동</span></p>" +
                "<p><strong>콜백 URL:</strong> <code>" + redirectUri + "</code></p>" +
                "<p><strong>포트:</strong> " + callbackPort + "</p>" +
                "<p><strong>현재 시간:</strong> " + new java.util.Date() + "</p>" +
                "</div>" +
                "<h3>🔗 연결 테스트</h3>" +
                "<p>이 페이지가 보인다면 콜백 서버가 정상적으로 작동하고 있습니다.</p>" +
                "<p><a href='/debug'>🔧 디버그 정보 보기</a></p>" +
                "<hr>" +
                "<h3>📋 설정 가이드</h3>" +
                "<ol>" +
                "<li><strong>치지직 개발자 센터</strong>에서 로그인 리디렉션 URL을 다음으로 설정:</li>" +
                "<li><code>" + redirectUri + "</code></li>" +
                "<li>포트포워딩이 정상적으로 설정되었는지 확인</li>" +
                "<li>방화벽에서 포트 " + callbackPort + " 허용 설정</li>" +
                "</ol>" +
                "</body></html>";

        sendResponse(exchange, 200, statusInfo);
    }

    /**
     * 초기화 시 콜백 URL 검증
     */
    public CompletableFuture<Boolean> initialize() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 설정 검증
                if (clientId.isEmpty() || clientSecret.isEmpty()) {
                    plugin.getLogger().warning("⚠️ Client ID 또는 Client Secret이 설정되지 않았습니다.");
                    return false;
                }

                startCallbackServer();

                // 콜백 URL 접근성 테스트
                if (!redirectUri.contains("localhost")) {
                    testCallbackUrlAccessibility();
                }

                // **새로 추가: 서버 시작 시 기존 연결된 사용자들 자동 재연결 (기존 메서드 활용)**
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        autoReconnectStoredUsers();
                    }
                }.runTaskLater(plugin, 100L); // 5초 후 실행

                plugin.getLogger().info("✅ 치지직 OAuth + Socket.IO 연동 관리자 초기화 완료!");
                plugin.getLogger().info("🔄 오프라인 토큰 검증 및 자동 유지보수 시스템 활성화!");
                return true;
            } catch (Exception e) {
                plugin.getLogger().severe("❌ 치지직 OAuth 초기화 실패: " + e.getMessage());
                return false;
            }
        });
    }

    /**
     * 저장된 사용자들 자동 재연결 (기존 메서드들만 사용)
     */
    private void autoReconnectStoredUsers() {
        if (userTokens.isEmpty()) {
            plugin.getLogger().info("📋 저장된 치지직 연동 사용자가 없습니다.");
            return;
        }

        plugin.getLogger().info("🔄 저장된 " + userTokens.size() + "명의 사용자 토큰 상태 확인 중...");

        for (Map.Entry<String, UserAuth> entry : userTokens.entrySet()) {
            String playerName = entry.getKey();
            UserAuth userAuth = entry.getValue();

            Player player = Bukkit.getPlayer(playerName);
            if (player != null && player.isOnline()) {
                plugin.getLogger().info("🔄 " + playerName + " 온라인 - 재연결 시도");

                // 기존 reconnectWithRefreshToken 메서드 사용
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        // 기존 Socket 정리 (기존 방식)
                        Socket existingSocket = activeSockets.get(playerName);
                        if (existingSocket != null) {
                            existingSocket.disconnect();
                            existingSocket.close();
                            activeSockets.remove(playerName);
                        }

                        // 기존 reconnectWithRefreshToken 메서드 사용
                        reconnectWithRefreshToken(playerName, userAuth);
                    }
                }.runTaskLater(plugin, 40L * entry.getKey().hashCode() % 10); // 각자 다른 시간에 실행

            } else {
                plugin.getLogger().info("📋 " + playerName + " 오프라인 - 접속 시 자동 재연결 예정");
            }
        }
    }

    /**
     * 콜백 URL 접근성 테스트
     */
    private void testCallbackUrlAccessibility() {
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    String testUrl = redirectUri.replace("/callback", "/status");
                    URL url = new URL(testUrl);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);

                    int responseCode = conn.getResponseCode();
                    if (responseCode == 200) {
                        plugin.getLogger().info("✅ 콜백 URL 외부 접근 테스트 성공!");
                    } else {
                        plugin.getLogger().warning("⚠️ 콜백 URL 외부 접근 실패 (" + responseCode + ")");
                        plugin.getLogger().warning("💡 포트포워딩 설정을 확인하세요.");
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("⚠️ 콜백 URL 접근성 테스트 실패: " + e.getMessage());
                    plugin.getLogger().warning("💡 네트워크 설정을 확인하세요.");
                }
            }
        }.runTaskAsynchronously(plugin);
    }
    /**
     * 초기화 - 로컬 콜백 서버 시작
     public CompletableFuture<Boolean> initialize() {
     return CompletableFuture.supplyAsync(() -> {
     try {
     // 설정 검증
     if (clientId.isEmpty() || clientSecret.isEmpty()) {
     plugin.getLogger().warning("⚠️ Client ID 또는 Client Secret이 설정되지 않았습니다.");
     plugin.getLogger().warning("⚠️ config.yml에서 chzzk.client-id와 chzzk.client-secret을 설정하세요.");
     return false;
     }

     startCallbackServer();
     plugin.getLogger().info("✅ 치지직 OAuth + Socket.IO 연동 관리자 초기화 완료!");
     plugin.getLogger().info("📡 콜백 서버 실행 중: " + redirectUri);
     plugin.getLogger().info("🔗 실시간 후원 시스템 준비 완료!");
     return true;
     } catch (Exception e) {
     plugin.getLogger().severe("❌ 치지직 OAuth 초기화 실패: " + e.getMessage());
     e.printStackTrace();
     return false;
     }
     });
     }
     */
    /**
     * 로컬 콜백 서버 시작
     private void startCallbackServer() throws IOException {
     try {
     callbackServer = HttpServer.create(new InetSocketAddress(8080), 0);

     // OAuth 콜백 핸들러
     callbackServer.createContext("/callback", new HttpHandler() {
    @Override public void handle(HttpExchange exchange) throws IOException {
    handleCallback(exchange);
    }
    });

     // 디버그 페이지 핸들러
     callbackServer.createContext("/debug", new HttpHandler() {
    @Override public void handle(HttpExchange exchange) throws IOException {
    handleDebug(exchange);
    }
    });

     callbackServer.start();
     plugin.getLogger().info("✅ 콜백 서버 시작 성공 (포트: 8080)");
     plugin.getLogger().info("🔧 디버그 페이지: http://localhost:8080/debug");
     } catch (IOException e) {
     plugin.getLogger().severe("❌ 콜백 서버 시작 실패: " + e.getMessage());
     throw e;
     }
     }
     */
    /**
     * 디버그 페이지 처리
     */
    private void handleDebug(HttpExchange exchange) throws IOException {
        String debugInfo = "<html><body style='font-family: Arial, sans-serif; padding: 20px;'>" +
                "<h2>치지직 OAuth + Socket.IO 디버그 정보</h2>" +
                "<p><strong>Client ID:</strong> " + clientId + "</p>" +
                "<p><strong>Redirect URI:</strong> " + redirectUri + "</p>" +
                "<p><strong>활성 인증:</strong> " + pendingAuth.size() + "개</p>" +
                "<p><strong>등록된 사용자:</strong> " + userTokens.size() + "명</p>" +
                "<p><strong>Socket.IO 연결:</strong> " + activeSockets.size() + "개</p>" +
                "<p><strong>활성 세션:</strong> " + sessionKeys.size() + "개</p>" +
                "<hr>" +
                "<h3>연결 상태</h3>";

        for (Map.Entry<String, UserAuth> entry : userTokens.entrySet()) {
            String playerName = entry.getKey();
            boolean hasSocket = activeSockets.containsKey(playerName);
            boolean hasSession = sessionKeys.containsKey(playerName);

            debugInfo += "<p><strong>" + playerName + ":</strong> " +
                    "Socket: " + (hasSocket ? "✅" : "❌") + ", " +
                    "Session: " + (hasSession ? "✅" : "❌") + "</p>";
        }

        debugInfo += "<hr>" +
                "<p>📝 <a href='https://developers.chzzk.naver.com'>치지직 개발자 센터</a>에서 다음을 확인하세요:</p>" +
                "<ul>" +
                "<li>로그인 리디렉션 URL: <code>" + redirectUri + "</code></li>" +
                "<li>API Scopes: 후원 조회</li>" +
                "<li>애플리케이션 상태: 승인됨</li>" +
                "</ul>" +
                "</body></html>";

        sendResponse(exchange, 200, debugInfo);
    }

    /**
     * HTTP 콜백 처리
     */
    private void handleCallback(HttpExchange exchange) throws IOException {
        try {
            String query = exchange.getRequestURI().getQuery();
            Map<String, String> params = parseQuery(query);

            String code = params.get("code");
            String state = params.get("state");
            String error = params.get("error");

            if (error != null) {
                plugin.getLogger().severe("❌ OAuth 인증 오류: " + error);
                sendResponse(exchange, 400, "인증 실패: " + error);
                return;
            }

            if (code == null || state == null) {
                plugin.getLogger().severe("❌ 잘못된 콜백 파라미터: code=" + code + ", state=" + state);
                sendResponse(exchange, 400, "잘못된 콜백 파라미터");
                return;
            }

            String storedValue = pendingAuth.get(state);
            if (storedValue == null) {
                plugin.getLogger().severe("❌ 인증 상태를 찾을 수 없음: " + state);
                sendResponse(exchange, 400, "인증 상태를 찾을 수 없습니다.");
                return;
            }

            // UUID와 플레이어 이름 분리
            String playerName;
            if (storedValue.contains(":")) {
                String[] parts = storedValue.split(":", 2);
                String uuid = parts[0];
                playerName = parts[1];

                // UUID로 플레이어 재확인
                Player player = Bukkit.getPlayer(UUID.fromString(uuid));
                if (player != null) {
                    playerName = player.getName(); // 최신 닉네임 사용
                }
            } else {
                playerName = storedValue; // 폴백
            }

            // Access Token 발급 및 Socket.IO 연결 시작
            processOAuthCallback(code, state, playerName);

            sendResponse(exchange, 200,
                    "<html><body style='font-family: Arial, sans-serif; text-align: center; padding: 50px;'>" +
                            "<h2 style='color: #4CAF50;'>✅ 치지직 연동 성공!</h2>" +
                            "<p>실시간 후원 시스템이 활성화되었습니다.</p>" +
                            "<p>마인크래프트로 돌아가세요.</p>" +
                            "<script>setTimeout(function(){window.close();}, 3000);</script>" +
                            "</body></html>");

        } catch (Exception e) {
            plugin.getLogger().severe("콜백 처리 오류: " + e.getMessage());
            e.printStackTrace();
            sendResponse(exchange, 500, "서버 오류: " + e.getMessage());
        }
    }

    /**
     * OAuth 콜백 처리
     */
    private void processOAuthCallback(String code, String state, String playerName) {
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    plugin.getLogger().info("🔄 " + playerName + " Access Token 발급 시작...");

                    // Access Token 발급
                    JsonObject tokenResponse = exchangeCodeForToken(code, state);

                    if (tokenResponse != null) {
                        String accessToken = tokenResponse.get("accessToken").getAsString();
                        String refreshToken = tokenResponse.get("refreshToken").getAsString();

                        plugin.getLogger().info("✅ " + playerName + " Access Token 발급 성공");
                        plugin.getLogger().info("🔄 " + playerName + " Socket.IO 세션 생성 시작...");

                        // Socket.IO 세션 생성
                        JsonObject sessionResponse = createSocketSession(accessToken);

                        if (sessionResponse != null) {
                            String sessionUrl = sessionResponse.get("url").getAsString();

                            plugin.getLogger().info("✅ " + playerName + " Socket.IO 세션 생성 성공");
                            plugin.getLogger().info("🔗 세션 URL: " + sessionUrl);

                            // 사용자 토큰 저장
                            UserAuth userAuth = new UserAuth(accessToken, refreshToken, playerName, sessionUrl);
                            userTokens.put(playerName, userAuth);
                            saveUserTokens();

                            // Socket.IO 연결 시작
                            connectToSocketIO(playerName, userAuth);

                            // 메인 스레드에서 플레이어에게 알림
                            new BukkitRunnable() {
                                @Override
                                public void run() {
                                    Player player = Bukkit.getPlayer(playerName);
                                    if (player != null) {
                                        player.sendMessage(ChatColor.GREEN + "✅ 치지직 연동 성공!");
                                        player.sendMessage(ChatColor.AQUA + "채널: " + playerName);
                                        player.sendMessage(ChatColor.YELLOW + "🔗 실시간 후원 시스템 활성화!");
                                    }
                                }
                            }.runTask(plugin);

                            plugin.getLogger().info("✅ " + playerName + " 치지직 OAuth + Socket.IO 연동 완료");
                        } else {
                            plugin.getLogger().severe("❌ " + playerName + " Socket.IO 세션 생성 실패");
                        }
                    } else {
                        plugin.getLogger().severe("❌ " + playerName + " Access Token 발급 실패");
                    }

                } catch (Exception e) {
                    plugin.getLogger().severe("❌ " + playerName + " OAuth 콜백 처리 오류: " + e.getMessage());
                    e.printStackTrace();
                }

                // 임시 상태 제거
                pendingAuth.remove(state);
            }
        }.runTaskAsynchronously(plugin);
    }

    /**
     * 인증 코드를 Access Token으로 교환 (재시도 로직 추가)
     */
    private JsonObject exchangeCodeForToken(String code, String state) throws Exception {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                URL url = new URL(authBaseUrl + "/auth/v1/token");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();

                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("User-Agent", "CheezePlugin/1.0");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000); // 10초 연결 타임아웃
                conn.setReadTimeout(15000);    // 15초 읽기 타임아웃

                JsonObject requestJson = new JsonObject();
                requestJson.addProperty("grantType", "authorization_code");
                requestJson.addProperty("clientId", clientId);
                requestJson.addProperty("clientSecret", clientSecret);
                requestJson.addProperty("code", code);
                requestJson.addProperty("state", state);

                String jsonData = gson.toJson(requestJson);
                plugin.getLogger().info("🔄 토큰 교환 요청 (시도 " + attempt + "/" + MAX_RETRIES + "): " + jsonData);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(jsonData.getBytes("UTF-8"));
                    os.flush();
                }

                int responseCode = conn.getResponseCode();
                plugin.getLogger().info("📡 토큰 교환 응답 코드: " + responseCode);

                if (responseCode == 200) {
                    String response = readResponse(conn);
                    plugin.getLogger().info("✅ 토큰 교환 성공: " + response);
                    JsonObject json = JsonParser.parseString(response).getAsJsonObject();
                    return json.getAsJsonObject("content");
                } else {
                    String errorResponse = readErrorResponse(conn);
                    plugin.getLogger().severe("❌ 토큰 교환 실패 (응답 코드: " + responseCode + "): " + errorResponse);

                    if (attempt < MAX_RETRIES) {
                        plugin.getLogger().warning("⏳ " + RETRY_DELAY_MS + "ms 후 재시도...");
                        Thread.sleep(RETRY_DELAY_MS);
                    }
                }

            } catch (Exception e) {
                plugin.getLogger().severe("❌ 토큰 교환 오류 (시도 " + attempt + "/" + MAX_RETRIES + "): " + e.getMessage());
                if (attempt < MAX_RETRIES) {
                    plugin.getLogger().warning("⏳ " + RETRY_DELAY_MS + "ms 후 재시도...");
                    Thread.sleep(RETRY_DELAY_MS);
                } else {
                    throw e;
                }
            }
        }
        return null;
    }

    /**
     * Socket.IO 세션 생성 (재시도 로직 추가)
     */
    private JsonObject createSocketSession(String accessToken) throws Exception {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                URL url = new URL(apiBaseUrl + "/open/v1/sessions/auth");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();

                conn.setRequestMethod("GET");
                conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("User-Agent", "CheezePlugin/1.0");
                conn.setConnectTimeout(10000); // 10초 연결 타임아웃
                conn.setReadTimeout(15000);    // 15초 읽기 타임아웃

                plugin.getLogger().info("🔄 세션 생성 요청 (시도 " + attempt + "/" + MAX_RETRIES + ")");

                int responseCode = conn.getResponseCode();
                plugin.getLogger().info("📡 세션 생성 응답 코드: " + responseCode);

                if (responseCode == 200) {
                    String response = readResponse(conn);
                    plugin.getLogger().info("✅ 세션 생성 성공: " + response);
                    JsonObject json = JsonParser.parseString(response).getAsJsonObject();
                    return json.getAsJsonObject("content");
                } else if (responseCode == 401) {
                    plugin.getLogger().severe("❌ 인증 토큰이 무효합니다 (401): " + readErrorResponse(conn));
                    throw new SecurityException("INVALID_TOKEN");
                } else {
                    String errorResponse = readErrorResponse(conn);
                    plugin.getLogger().severe("❌ 세션 생성 실패 (응답 코드: " + responseCode + "): " + errorResponse);

                    if (attempt < MAX_RETRIES && responseCode >= 500) { // 서버 오류인 경우만 재시도
                        plugin.getLogger().warning("⏳ " + RETRY_DELAY_MS + "ms 후 재시도...");
                        Thread.sleep(RETRY_DELAY_MS);
                    } else {
                        break; // 클라이언트 오류인 경우 재시도하지 않음
                    }
                }

            } catch (Exception e) {
                plugin.getLogger().severe("❌ 세션 생성 오류 (시도 " + attempt + "/" + MAX_RETRIES + "): " + e.getMessage());
                if (attempt < MAX_RETRIES) {
                    plugin.getLogger().warning("⏳ " + RETRY_DELAY_MS + "ms 후 재시도...");
                    Thread.sleep(RETRY_DELAY_MS);
                } else {
                    throw e;
                }
            }
        }
        return null;
    }

    /**
     * Socket.IO 연결 및 후원 이벤트 구독 (개선된 버전)
     */
    private void connectToSocketIO(String playerName, UserAuth userAuth) {
        try {
            plugin.getLogger().info("🔄 " + playerName + " Socket.IO 연결 시작...");

            // Socket.IO 연결 옵션 설정 (v1.x 호환)
            IO.Options opts = new IO.Options();
            opts.transports = new String[]{"websocket", "polling"}; // polling 추가
            opts.reconnection = true;
            opts.reconnectionAttempts = 10; // 재연결 시도 횟수 증가
            opts.reconnectionDelay = 1000;  // 재연결 딜레이 감소
            opts.timeout = 20000;          // 타임아웃 증가
            opts.upgrade = false;          // 업그레이드 비활성화

            // Socket.IO 클라이언트 생성
            Socket socket = IO.socket(userAuth.sessionUrl, opts);
            // 연결 완료 리스너
            socket.on(Socket.EVENT_CONNECT, new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    plugin.getLogger().info("✅ " + playerName + " Socket.IO 연결 성공!");

                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            Player player = Bukkit.getPlayer(playerName);
                            if (player != null) {
                                player.sendMessage(ChatColor.GREEN + "🔗 실시간 후원 연결 성공!");
                            }
                        }
                    }.runTask(plugin);
                }
            });
            // 시스템 메시지 리스너
            socket.on("SYSTEM", new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    try {
                        String data = args[0].toString();
                        plugin.getLogger().info("📋 " + playerName + " 시스템 메시지: " + data);
                        JsonObject systemMessage = JsonParser.parseString(data).getAsJsonObject();

                        String type = systemMessage.get("type").getAsString();
                        JsonObject systemData = systemMessage.getAsJsonObject("data");

                        switch (type) {
                            case "connected":
                                String sessionKey = systemData.get("sessionKey").getAsString();
                                String oldSessionKey = sessionKeys.get(playerName);
                                sessionKeys.put(playerName, sessionKey);

                                plugin.getLogger().info("✅ " + playerName + " 세션 키 " + (oldSessionKey != null ? "갱신" : "획득") + ": " + sessionKey);

                                // 세션 키 획득 후 후원 이벤트 구독 (더 자세한 로그 추가)
                                plugin.getLogger().info("🔄 " + playerName + " 후원 이벤트 구독 시작...");
                                subscribeToDonationEventsWithRetry(playerName, sessionKey, userAuth.accessToken);
                                break;

                            case "subscribed":
                                String eventType = systemData.get("eventType").getAsString();
                                if ("DONATION".equals(eventType)) {
                                    plugin.getLogger().info("🎉 " + playerName + " 후원 이벤트 구독 완료!");

                                    // 플레이어에게 성공 알림
                                    new BukkitRunnable() {
                                        @Override
                                        public void run() {
                                            Player player = Bukkit.getPlayer(playerName);
                                            if (player != null) {
                                                player.sendMessage(ChatColor.GREEN + "🔗 치지직 실시간 후원 연결 완료!");
                                                player.sendMessage(ChatColor.AQUA + "💰 이제 후원을 실시간으로 받을 수 있습니다!");
                                            }
                                        }
                                    }.runTask(plugin);
                                }
                                break;

                            case "unsubscribed":
                                String unsubEventType = systemData.get("eventType").getAsString();
                                if ("DONATION".equals(unsubEventType)) {
                                    plugin.getLogger().warning("❌ " + playerName + " 후원 이벤트 구독 해제됨");
                                }
                                break;

                            case "revoked":
                                String revokedEventType = systemData.get("eventType").getAsString();
                                if ("DONATION".equals(revokedEventType)) {
                                    plugin.getLogger().warning("⚠️ " + playerName + " 후원 권한 취소됨");

                                    new BukkitRunnable() {
                                        @Override
                                        public void run() {
                                            Player player = Bukkit.getPlayer(playerName);
                                            if (player != null) {
                                                player.sendMessage(ChatColor.RED + "❌ 후원 권한이 취소되었습니다!");
                                                player.sendMessage(ChatColor.YELLOW + "치지직 개발자 센터에서 권한을 재승인해주세요.");
                                            }
                                        }
                                    }.runTask(plugin);
                                }
                                break;

                            default:
                                plugin.getLogger().info("❓ " + playerName + " 알 수 없는 시스템 메시지: " + type);
                                break;
                        }

                    } catch (Exception e) {
                        plugin.getLogger().severe("❌ " + playerName + " 시스템 메시지 처리 오류: " + e.getMessage());
                        e.printStackTrace();
                    }
                }
            });
            // 후원 이벤트 리스너
            socket.on("DONATION", new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    try {
                        String data = args[0].toString();
                        plugin.getLogger().info("💰 " + playerName + " 후원 이벤트 수신: " + data);
                        JsonObject donationEvent = JsonParser.parseString(data).getAsJsonObject();

                        // 후원 정보 추출
                        String donationType = donationEvent.get("donationType").getAsString();
                        String channelId = donationEvent.get("channelId").getAsString();
                        String donatorChannelId = donationEvent.get("donatorChannelId").getAsString();
                        String donatorNickname = donationEvent.get("donatorNickname").getAsString();
                        String payAmountStr = donationEvent.get("payAmount").getAsString();
                        int payAmount = Integer.parseInt(payAmountStr);
                        String donationText = donationEvent.has("donationText") ?
                                donationEvent.get("donationText").getAsString() : "";

                        // 중복 처리 방지
                        String donationId = donatorChannelId + "_" + payAmount + "_" + System.currentTimeMillis();
                        synchronized (processedDonations) {
                            if (processedDonations.contains(donationId)) {
                                plugin.getLogger().info("⚠️ 중복 후원 이벤트 무시: " + donationId);
                                return;
                            }
                            processedDonations.add(donationId);

                            // 처리된 후원 ID 정리 (1000개 이상 시 오래된 것 제거)
                            if (processedDonations.size() > 1000) {
                                processedDonations.clear();
                                plugin.getLogger().info("🧹 처리된 후원 ID 목록 정리 완료");
                            }
                        }

                        // 메인 스레드에서 후원 이벤트 처리
                        new BukkitRunnable() {
                            @Override
                            public void run() {
                                Player player = Bukkit.getPlayer(playerName);
                                if (player != null) {
                                    donationPlugin.processDonationEvent(player, payAmount, donatorNickname, playerName);
                                    plugin.getLogger().info("🎉 " + playerName + " 후원 처리 완료: " + donatorNickname + " (" + payAmount + "원)");

                                    if (!donationText.isEmpty()) {
                                        plugin.getLogger().info("💬 후원 메시지: " + donationText);
                                    }
                                } else {
                                    plugin.getLogger().warning("⚠️ 플레이어 " + playerName + "이(가) 오프라인 상태입니다");
                                }
                            }
                        }.runTask(plugin);

                    } catch (Exception e) {
                        plugin.getLogger().severe("❌ " + playerName + " 후원 이벤트 처리 오류: " + e.getMessage());
                        e.printStackTrace();
                    }
                }
            });
            // 연결 해제 리스너
            socket.on(Socket.EVENT_DISCONNECT, new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    String reason = args.length > 0 ? args[0].toString() : "unknown";

                    plugin.getLogger().warning("🔌 " + playerName + " Socket.IO 연결 해제: " + reason);

                    // 더 자세한 해제 사유 분석
                    if ("io server disconnect".equals(reason)) {
                        plugin.getLogger().warning("📡 " + playerName + " 서버에서 연결 해제 - 권한 문제 또는 구독 실패 의심");

                        new BukkitRunnable() {
                            @Override
                            public void run() {
                                Player player = Bukkit.getPlayer(playerName);
                                if (player != null) {
                                    player.sendMessage(ChatColor.RED + "❌ 치지직 서버에서 연결을 해제했습니다!");
                                    player.sendMessage(ChatColor.YELLOW + "💡 원인: 후원 이벤트 구독 권한 문제");
                                    player.sendMessage(ChatColor.AQUA + "🔧 해결 방법:");
                                    player.sendMessage(ChatColor.WHITE + "1. '/치즈설정 재연결'");
                                    player.sendMessage(ChatColor.WHITE + "2. '/치즈설정 해제' 후 '/치즈설정 연결' 재시도");
                                }
                            }
                        }.runTask(plugin);

                    } else if ("transport close".equals(reason)) {
                        plugin.getLogger().info("📡 " + playerName + " 네트워크 연결 해제 - 자동 재연결 시도 중");
                    } else {
                        plugin.getLogger().warning("❓ " + playerName + " 알 수 없는 해제 사유: " + reason);
                    }

                    activeSockets.remove(playerName);
                }
            });
            // 재연결 성공 시 세션 키 복구
            socket.on("reconnect", new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    plugin.getLogger().info("🔄 " + playerName + " Socket.IO 재연결 성공!");

                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            Player player = Bukkit.getPlayer(playerName);
                            if (player != null) {
                                player.sendMessage(ChatColor.GREEN + "🔄 실시간 후원 연결이 복구되었습니다!");
                            }
                        }
                    }.runTask(plugin);
                }
            });
            // 오류 리스너
            socket.on(Socket.EVENT_CONNECT_ERROR, new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    plugin.getLogger().severe("❌ " + playerName + " Socket.IO 연결 오류: " + args[0]);

                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            Player player = Bukkit.getPlayer(playerName);
                            if (player != null) {
                                player.sendMessage(ChatColor.RED + "❌ 실시간 후원 연결 실패!");
                                player.sendMessage(ChatColor.YELLOW + "다시 시도하려면 '/치즈설정 연결'을 사용하세요.");
                            }
                        }
                    }.runTask(plugin);
                }
            });
            // 소켓 저장 및 연결 시작
            activeSockets.put(playerName, socket);
            socket.connect();
        } catch (Exception e) {
            plugin.getLogger().severe("❌ " + playerName + " Socket.IO 연결 생성 오류: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 후원 이벤트 구독 (재시도 로직 포함) - 채널 ID 및 다양한 방법 시도
     */
    private void subscribeToDonationEventsWithRetry(String playerName, String sessionKey, String accessToken) {
        new BukkitRunnable() {
            @Override
            public void run() {
                // 먼저 사용자 채널 정보를 가져와서 채널 ID 확인
                String channelId = getCurrentUserChannelId(accessToken);
                plugin.getLogger().info("🆔 " + playerName + " 채널 ID: " + (channelId != null ? channelId : "없음"));

                // 여러 가지 접근 방법 시도
                if (tryDirectSubscription(playerName, sessionKey, accessToken, channelId)) return;
                if (tryQueryParameterSubscription(playerName, sessionKey, accessToken)) return;
                if (tryGetMethodSubscription(playerName, sessionKey, accessToken)) return;
                if (tryChannelBasedSubscription(playerName, sessionKey, accessToken, channelId)) return;

                // 모든 방법 실패
                plugin.getLogger().severe("💥 " + playerName + " 모든 구독 방법 실패!");
                notifySubscriptionFailure(playerName);
            }
        }.runTaskAsynchronously(plugin);
    }

    /**
     * 현재 사용자의 채널 ID 가져오기
     */
    private String getCurrentUserChannelId(String accessToken) {
        try {
            URL url = new URL(apiBaseUrl + "/open/v1/users/me");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();

            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);

            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                String response = readResponse(conn);
                JsonObject json = JsonParser.parseString(response).getAsJsonObject();
                JsonObject content = json.getAsJsonObject("content");
                if (content != null && content.has("channelId")) {
                    return content.get("channelId").getAsString();
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("채널 ID 조회 실패: " + e.getMessage());
        }
        return null;
    }

    /**
     * 직접 POST 구독 (기존 방식 + 채널 ID 포함)
     */
    private boolean tryDirectSubscription(String playerName, String sessionKey, String accessToken, String channelId) {
        plugin.getLogger().info("🔄 " + playerName + " 직접 POST 구독 시도");

        try {
            URL url = new URL(apiBaseUrl + "/open/v1/sessions/events/subscribe/donation");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();

            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);

            // 여러 요청 형식 시도
            JsonObject[] requests = {
                    createBasicRequest(sessionKey),
                    createRequestWithEventType(sessionKey, "DONATION"),
                    createRequestWithChannelId(sessionKey, channelId),
                    createFullRequest(sessionKey, "DONATION", channelId)
            };

            for (JsonObject request : requests) {
                if (request == null) continue;

                String jsonData = gson.toJson(request);
                plugin.getLogger().info("📡 " + playerName + " POST 요청: " + jsonData);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(jsonData.getBytes("UTF-8"));
                    os.flush();
                }

                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    String response = readResponse(conn);
                    plugin.getLogger().info("✅ " + playerName + " POST 구독 성공: " + response);
                    return true;
                } else {
                    String errorResponse = readErrorResponse(conn);
                    plugin.getLogger().info("⚠️ " + playerName + " POST 실패 (" + responseCode + "): " + errorResponse);
                }

                // 새 연결 생성
                conn.disconnect();
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setRequestProperty("Accept", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(15000);
            }

        } catch (Exception e) {
            plugin.getLogger().severe("❌ " + playerName + " POST 구독 오류: " + e.getMessage());
        }

        return false;
    }

    /**
     * 쿼리 파라미터 방식 시도
     */
    private boolean tryQueryParameterSubscription(String playerName, String sessionKey, String accessToken) {
        plugin.getLogger().info("🔄 " + playerName + " 쿼리 파라미터 구독 시도");

        try {
            String urlString = apiBaseUrl + "/open/v1/sessions/events/subscribe/donation?sessionKey=" +
                    URLEncoder.encode(sessionKey, "UTF-8") + "&eventType=DONATION";
            URL url = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();

            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);

            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                String response = readResponse(conn);
                plugin.getLogger().info("✅ " + playerName + " 쿼리 파라미터 구독 성공: " + response);
                return true;
            } else {
                String errorResponse = readErrorResponse(conn);
                plugin.getLogger().info("⚠️ " + playerName + " 쿼리 파라미터 실패 (" + responseCode + "): " + errorResponse);
            }

        } catch (Exception e) {
            plugin.getLogger().severe("❌ " + playerName + " 쿼리 파라미터 구독 오류: " + e.getMessage());
        }

        return false;
    }

    /**
     * GET 메서드 시도
     */
    private boolean tryGetMethodSubscription(String playerName, String sessionKey, String accessToken) {
        plugin.getLogger().info("🔄 " + playerName + " GET 메서드 구독 시도");

        try {
            String urlString = apiBaseUrl + "/open/v1/sessions/events/subscribe/donation?sessionKey=" +
                    URLEncoder.encode(sessionKey, "UTF-8");
            URL url = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();

            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);

            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                String response = readResponse(conn);
                plugin.getLogger().info("✅ " + playerName + " GET 구독 성공: " + response);
                return true;
            } else {
                String errorResponse = readErrorResponse(conn);
                plugin.getLogger().info("⚠️ " + playerName + " GET 실패 (" + responseCode + "): " + errorResponse);
            }

        } catch (Exception e) {
            plugin.getLogger().severe("❌ " + playerName + " GET 구독 오류: " + e.getMessage());
        }

        return false;
    }

    /**
     * 채널 기반 구독 시도
     */
    private boolean tryChannelBasedSubscription(String playerName, String sessionKey, String accessToken, String channelId) {
        if (channelId == null) return false;

        plugin.getLogger().info("🔄 " + playerName + " 채널 기반 구독 시도");

        try {
            String urlString = apiBaseUrl + "/open/v1/channels/" + channelId + "/events/subscribe/donation";
            URL url = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();

            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);

            JsonObject request = new JsonObject();
            request.addProperty("sessionKey", sessionKey);

            String jsonData = gson.toJson(request);
            plugin.getLogger().info("📡 " + playerName + " 채널 기반 요청: " + jsonData);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonData.getBytes("UTF-8"));
                os.flush();
            }

            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                String response = readResponse(conn);
                plugin.getLogger().info("✅ " + playerName + " 채널 기반 구독 성공: " + response);
                return true;
            } else {
                String errorResponse = readErrorResponse(conn);
                plugin.getLogger().info("⚠️ " + playerName + " 채널 기반 실패 (" + responseCode + "): " + errorResponse);
            }

        } catch (Exception e) {
            plugin.getLogger().severe("❌ " + playerName + " 채널 기반 구독 오류: " + e.getMessage());
        }

        return false;
    }

    // 요청 생성 헬퍼 메서드들
    private JsonObject createBasicRequest(String sessionKey) {
        JsonObject request = new JsonObject();
        request.addProperty("sessionKey", sessionKey);
        return request;
    }

    private JsonObject createRequestWithEventType(String sessionKey, String eventType) {
        JsonObject request = new JsonObject();
        request.addProperty("sessionKey", sessionKey);
        request.addProperty("eventType", eventType);
        return request;
    }

    private JsonObject createRequestWithChannelId(String sessionKey, String channelId) {
        if (channelId == null) return null;
        JsonObject request = new JsonObject();
        request.addProperty("sessionKey", sessionKey);
        request.addProperty("channelId", channelId);
        return request;
    }

    private JsonObject createFullRequest(String sessionKey, String eventType, String channelId) {
        if (channelId == null) return null;
        JsonObject request = new JsonObject();
        request.addProperty("sessionKey", sessionKey);
        request.addProperty("eventType", eventType);
        request.addProperty("channelId", channelId);
        return request;
    }

    /**
     * 구독 실패 알림
     */
    private void notifySubscriptionFailure(String playerName) {
        new BukkitRunnable() {
            @Override
            public void run() {
                Player player = Bukkit.getPlayer(playerName);
                if (player != null) {
                    player.sendMessage(ChatColor.RED + "❌ 후원 이벤트 구독에 실패했습니다!");
                    player.sendMessage(ChatColor.YELLOW + "권한 승인을 기다리거나 개발자 센터 설정을 확인해주세요.");
                    player.sendMessage(ChatColor.GRAY + "일부 API 권한은 승인까지 시간이 걸릴 수 있습니다.");
                }
            }
        }.runTask(plugin);
    }

    /**
     * 후원 이벤트 구독 취소
     */
    private void unsubscribeFromDonationEvents(String playerName, String sessionKey, String accessToken) {
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    URL url = new URL(apiBaseUrl + "/open/v1/sessions/events/unsubscribe/donation");
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();

                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                    conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                    conn.setRequestProperty("Accept", "application/json");
                    conn.setRequestProperty("User-Agent", "CheezePlugin/1.0");
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(15000);

                    JsonObject requestJson = new JsonObject();
                    requestJson.addProperty("sessionKey", sessionKey);

                    String jsonData = gson.toJson(requestJson);

                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(jsonData.getBytes("UTF-8"));
                        os.flush();
                    }

                    int responseCode = conn.getResponseCode();
                    if (responseCode == 200) {
                        plugin.getLogger().info("✅ " + playerName + " 후원 이벤트 구독 취소 성공");
                    } else {
                        plugin.getLogger().warning("❌ " + playerName + " 후원 이벤트 구독 취소 실패: " + responseCode);
                        plugin.getLogger().warning("응답: " + readErrorResponse(conn));
                    }

                } catch (Exception e) {
                    plugin.getLogger().severe("❌ " + playerName + " 후원 이벤트 구독 취소 오류: " + e.getMessage());
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    /**
     * OAuth 인증 시작
     */
    public String startOAuthFlow(String playerName) {
        try {
            String state = UUID.randomUUID().toString().replace("-", "");

            // 기존: 플레이어 이름으로 저장
            // pendingAuth.put(state, playerName);

            // 개선: UUID와 플레이어 이름을 함께 저장
            Player player = Bukkit.getPlayer(playerName);
            if (player != null) {
                pendingAuth.put(state, player.getUniqueId().toString() + ":" + playerName);
            } else {
                pendingAuth.put(state, playerName); // 폴백
            }

            // 5분 후 자동 만료
            new BukkitRunnable() {
                @Override
                public void run() {
                    pendingAuth.remove(state);
                    plugin.getLogger().info("⏰ " + playerName + " OAuth 인증 상태 만료: " + state);
                }
            }.runTaskLater(plugin, 6000L);

            // URL 인코딩 강화
            String authUrl = "https://chzzk.naver.com/account-interlock" +
                    "?clientId=" + URLEncoder.encode(clientId, "UTF-8") +
                    "&redirectUri=" + URLEncoder.encode(redirectUri, "UTF-8") +
                    "&state=" + state; // state는 UUID이므로 인코딩 불필요

            plugin.getLogger().info("📋 " + playerName + " OAuth URL 생성 완료");
            return authUrl;

        } catch (Exception e) {
            plugin.getLogger().severe("❌ " + playerName + " OAuth URL 생성 오류: " + e.getMessage());
            return null;
        }
    }

    /**
     * 플레이어 연결 해제
     */
    public CompletableFuture<Boolean> disconnectPlayer(String playerName) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                UserAuth userAuth = userTokens.get(playerName);
                String sessionKey = sessionKeys.get(playerName);

                // 후원 이벤트 구독 취소
                if (userAuth != null && sessionKey != null) {
                    unsubscribeFromDonationEvents(playerName, sessionKey, userAuth.accessToken);
                }

                // Socket.IO 연결 해제
                Socket socket = activeSockets.remove(playerName);
                if (socket != null) {
                    socket.disconnect();
                    socket.close();
                }

                // 세션 키 제거
                sessionKeys.remove(playerName);

                // 토큰 제거
                userTokens.remove(playerName);
                saveUserTokens();

                plugin.getLogger().info("🔌 " + playerName + " 치지직 연결 해제 완료");
                return true;

            } catch (Exception e) {
                plugin.getLogger().severe("❌ " + playerName + " 연결 해제 오류: " + e.getMessage());
                return false;
            }
        });
    }

    /**
     * 모든 연결 해제
     */
    public void disconnectAll() {
        plugin.getLogger().info("🔌 모든 치지직 연결 해제 중...");

        // 모든 플레이어 연결 해제
        for (String playerName : new HashSet<>(userTokens.keySet())) {
            disconnectPlayer(playerName).join();
        }

        // 콜백 서버 종료
        if (callbackServer != null) {
            callbackServer.stop(0);
        }

        // 처리된 후원 ID 정리
        processedDonations.clear();

        plugin.getLogger().info("✅ 모든 치지직 연결 해제 완료");
    }

    /**
     * 연결 상태 확인 (플레이어에게도 메시지 전송)
     */
    public void printConnectionStatus() {
        plugin.getLogger().info("📊 === 치지직 OAuth + Socket.IO 연결 상태 ===");
        plugin.getLogger().info("등록된 사용자: " + userTokens.size() + "명");
        plugin.getLogger().info("Socket.IO 연결: " + activeSockets.size() + "개");
        plugin.getLogger().info("활성 세션: " + sessionKeys.size() + "개");

        for (Map.Entry<String, UserAuth> entry : userTokens.entrySet()) {
            String playerName = entry.getKey();
            boolean hasSocket = activeSockets.containsKey(playerName);
            boolean hasSession = sessionKeys.containsKey(playerName);
            plugin.getLogger().info("👤 " + playerName + ": Socket=" + (hasSocket ? "✅" : "❌") +
                    ", Session=" + (hasSession ? "✅" : "❌"));
        }
    }

    /**
     * 플레이어에게 연결 상태 전송
     */
    public void printConnectionStatusToPlayer(Player player) {
        player.sendMessage(ChatColor.GOLD + "📊 === 치지직 OAuth + Socket.IO 연결 상태 ===");
        player.sendMessage(ChatColor.WHITE + "등록된 사용자: " + ChatColor.YELLOW + userTokens.size() + "명");
        player.sendMessage(ChatColor.WHITE + "Socket.IO 연결: " + ChatColor.YELLOW + activeSockets.size() + "개");
        player.sendMessage(ChatColor.WHITE + "활성 세션: " + ChatColor.YELLOW + sessionKeys.size() + "개");

        if (userTokens.isEmpty()) {
            player.sendMessage(ChatColor.GRAY + "연결된 사용자가 없습니다.");
            return;
        }

        player.sendMessage(ChatColor.AQUA + "=== 연결된 사용자 목록 ===");
        for (Map.Entry<String, UserAuth> entry : userTokens.entrySet()) {
            String playerName = entry.getKey();
            boolean hasSocket = activeSockets.containsKey(playerName);
            boolean hasSession = sessionKeys.containsKey(playerName);

            ChatColor nameColor = playerName.equals(player.getName()) ? ChatColor.GREEN : ChatColor.WHITE;
            String socketStatus = hasSocket ? ChatColor.GREEN + "✅ 연결됨" : ChatColor.RED + "❌ 끊어짐";
            String sessionStatus = hasSession ? ChatColor.GREEN + "✅ 활성" : ChatColor.RED + "❌ 비활성";

            // 후원 권한 승인 상태 확인 추가
            String donationStatus = ChatColor.YELLOW + "🔄 확인 중...";
            if (hasSocket && hasSession) {
                donationStatus = ChatColor.GREEN + "✅ 후원 감지 가능";
            } else if (hasSocket) {
                donationStatus = ChatColor.YELLOW + "⚠️ 권한 승인 대기";
            } else {
                donationStatus = ChatColor.RED + "❌ 연결 끊어짐";
            }

            player.sendMessage(nameColor + "👤 " + playerName + ":");
            player.sendMessage(ChatColor.WHITE + "  Socket.IO: " + socketStatus);
            player.sendMessage(ChatColor.WHITE + "  세션: " + sessionStatus);
            player.sendMessage(ChatColor.WHITE + "  후원 감지: " + donationStatus);
        }

        player.sendMessage(ChatColor.YELLOW + "💡 후원 이벤트는 개발자 센터에서 권한 승인 후 사용 가능합니다.");
    }

    /**
     * 플레이어가 연결되어 있는지 확인
     */
    public boolean isPlayerConnected(String playerName) {
        return userTokens.containsKey(playerName);
    }

    /**
     * 연결된 플레이어 목록 반환
     */
    public Set<String> getConnectedPlayers() {
        return new HashSet<>(userTokens.keySet());
    }

    /**
     * 특정 플레이어의 인증 정보 반환
     */
    public UserAuth getPlayerAuth(String playerName) {
        return userTokens.get(playerName);
    }

    /**
     * 플레이어 재접속 시 처리 (기존 메서드들만 사용하여 개선)
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        String playerName = event.getPlayer().getName();
        UserAuth userAuth = userTokens.get(playerName);

        if (userAuth != null) {
            plugin.getLogger().info("🔄 " + playerName + " 플레이어 재접속 - 토큰 검증 및 재연결 시도");

            event.getPlayer().sendMessage(ChatColor.GREEN + "✅ 치지직 연동 자동 복구 중...");
            event.getPlayer().sendMessage(ChatColor.AQUA + "채널: " + playerName);
            event.getPlayer().sendMessage(ChatColor.YELLOW + "🔗 토큰 검증 및 재연결 중...");

            // 기존 Socket 정리 (기존 방식 사용)
            Socket existingSocket = activeSockets.get(playerName);
            if (existingSocket != null && existingSocket.connected()) {
                existingSocket.disconnect();
                existingSocket.close();
                activeSockets.remove(playerName);
            }

            // 기존 reconnectWithRefreshToken 메서드를 더 안정적으로 호출
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (event.getPlayer().isOnline()) {
                        reconnectWithRefreshToken(playerName, userAuth);
                    }
                }
            }.runTaskLater(plugin, 60L); // 3초 후 실행 (기존 40L에서 증가)
        }
    }

    // 유틸리티 메서드들
    private Map<String, String> parseQuery(String query) {
        Map<String, String> params = new HashMap<>();
        if (query != null) {
            String[] pairs = query.split("&");
            for (String pair : pairs) {
                String[] keyValue = pair.split("=");
                if (keyValue.length == 2) {
                    try {
                        params.put(keyValue[0], URLDecoder.decode(keyValue[1], "UTF-8"));
                    } catch (UnsupportedEncodingException e) {
                        plugin.getLogger().warning("URL 디코딩 실패: " + pair);
                    }
                }
            }
        }
        return params;
    }

    private void sendResponse(HttpExchange exchange, int code, String response) throws IOException {
        byte[] bytes = response.getBytes("UTF-8");
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
        exchange.getResponseHeaders().add("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private String readResponse(HttpURLConnection conn) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            return response.toString();
        }
    }

    private String readErrorResponse(HttpURLConnection conn) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getErrorStream(), "UTF-8"))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            return response.toString();
        } catch (Exception e) {
            return "오류 응답을 읽을 수 없습니다: " + e.getMessage();
        }
    }

    private void saveUserTokens() {
        try {
            File file = new File(plugin.getDataFolder(), "user_tokens.json");
            file.getParentFile().mkdirs();

            // UTF-8 인코딩 명시적 지정
            try (OutputStreamWriter writer = new OutputStreamWriter(
                    new FileOutputStream(file), StandardCharsets.UTF_8)) {
                gson.toJson(userTokens, writer);
            }

            plugin.getLogger().info("💾 사용자 토큰 저장 완료: " + userTokens.size() + "명");
        } catch (IOException e) {
            plugin.getLogger().severe("❌ 토큰 저장 오류: " + e.getMessage());
        }
    }

    private void loadUserTokens() {
        try {
            File file = new File(plugin.getDataFolder(), "user_tokens.json");
            if (file.exists()) {
                // UTF-8 인코딩 명시적 지정
                try (InputStreamReader reader = new InputStreamReader(
                        new FileInputStream(file), StandardCharsets.UTF_8)) {
                    Map<String, UserAuth> loaded = gson.fromJson(reader,
                            new com.google.gson.reflect.TypeToken<Map<String, UserAuth>>() {
                            }.getType());
                    if (loaded != null) {
                        userTokens.putAll(loaded);
                        plugin.getLogger().info("📂 사용자 토큰 로드 완료: " + userTokens.size() + "명");
                    }
                }
            }
        } catch (IOException e) {
            plugin.getLogger().severe("❌ 토큰 로드 오류: " + e.getMessage());
        }
    }

    // 메시지 출력 시 안전한 문자열 처리
    private void sendSafeMessage(Player player, String message) {
        try {
            player.sendMessage(message);
        } catch (Exception e) {
            // 한글 메시지 전송 실패 시 영어로 폴백
            player.sendMessage("Message delivery failed. Please check your client settings.");
            plugin.getLogger().warning("메시지 전송 실패: " + player.getName() + " - " + e.getMessage());
        }
    }

    private void safeBroadcast(String message) {
        try {
            Bukkit.broadcastMessage(message);
        } catch (Exception e) {
            plugin.getLogger().warning("브로드캐스트 실패: " + e.getMessage());
            // 개별 플레이어에게 전송 시도
            for (Player player : Bukkit.getOnlinePlayers()) {
                try {
                    player.sendMessage(message);
                } catch (Exception ex) {
                    plugin.getLogger().warning("플레이어 " + player.getName() + " 메시지 전송 실패");
                }
            }
        }
    }

    /**
     * Refresh Token을 사용하여 Access Token 갱신
     */
    private CompletableFuture<Boolean> refreshAccessToken(String playerName, UserAuth userAuth) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                plugin.getLogger().info("🔄 " + playerName + " Access Token 갱신 시도...");

                // 공식 API 엔드포인트로 토큰 갱신 요청
                URL url = new URL(authBaseUrl + "/auth/v1/token");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();

                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("User-Agent", "CheezePlugin/1.0");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(15000);

                // Refresh Token 요청 JSON 생성
                JsonObject requestJson = new JsonObject();
                requestJson.addProperty("grantType", "refresh_token");
                requestJson.addProperty("clientId", clientId);
                requestJson.addProperty("clientSecret", clientSecret);
                requestJson.addProperty("refreshToken", userAuth.refreshToken);

                String jsonData = gson.toJson(requestJson);
                plugin.getLogger().info("📡 " + playerName + " 토큰 갱신 요청: " + jsonData);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(jsonData.getBytes("UTF-8"));
                    os.flush();
                }

                int responseCode = conn.getResponseCode();
                plugin.getLogger().info("📡 " + playerName + " 토큰 갱신 응답 코드: " + responseCode);

                if (responseCode == 200) {
                    String response = readResponse(conn);
                    plugin.getLogger().info("✅ " + playerName + " 토큰 갱신 성공: " + response);

                    JsonObject json = JsonParser.parseString(response).getAsJsonObject();
                    JsonObject content = json.getAsJsonObject("content");

                    String newAccessToken = content.get("accessToken").getAsString();
                    String newRefreshToken = content.get("refreshToken").getAsString();

                    // 새 토큰으로 UserAuth 업데이트
                    UserAuth newUserAuth = new UserAuth(newAccessToken, newRefreshToken, playerName, userAuth.sessionUrl);
                    userTokens.put(playerName, newUserAuth);
                    saveUserTokens();

                    plugin.getLogger().info("✅ " + playerName + " 토큰 갱신 및 저장 완료");

                    // 메인 스레드에서 플레이어에게 알림
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            Player player = Bukkit.getPlayer(playerName);
                            if (player != null) {
                                player.sendMessage(ChatColor.GREEN + "🔄 치지직 토큰이 자동으로 갱신되었습니다!");
                            }
                        }
                    }.runTask(plugin);

                    return true;

                } else if (responseCode == 400 || responseCode == 401) {
                    // Refresh Token도 만료된 경우
                    String errorResponse = readErrorResponse(conn);
                    plugin.getLogger().warning("⚠️ " + playerName + " Refresh Token 만료: " + errorResponse);

                    // 토큰 제거 및 재인증 요청
                    userTokens.remove(playerName);
                    saveUserTokens();

                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            Player player = Bukkit.getPlayer(playerName);
                            if (player != null) {
                                player.sendMessage(ChatColor.YELLOW + "⚠️ 치지직 토큰이 만료되었습니다!");
                                player.sendMessage(ChatColor.AQUA + "'/치즈설정 연결'로 다시 연결해주세요.");
                            }
                        }
                    }.runTask(plugin);

                    return false;
                } else {
                    String errorResponse = readErrorResponse(conn);
                    plugin.getLogger().severe("❌ " + playerName + " 토큰 갱신 실패 (" + responseCode + "): " + errorResponse);
                    return false;
                }

            } catch (Exception e) {
                plugin.getLogger().severe("❌ " + playerName + " 토큰 갱신 오류: " + e.getMessage());
                e.printStackTrace();
                return false;
            }
        });
    }

    /**
     * Access Token 유효성 검사
     */
    private CompletableFuture<Boolean> validateAccessToken(String playerName, String accessToken) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 현재 사용자 정보 조회로 토큰 유효성 검사
                URL url = new URL(apiBaseUrl + "/open/v1/users/me");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();

                conn.setRequestMethod("GET");
                conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                conn.setRequestProperty("Accept", "application/json");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(10000);

                int responseCode = conn.getResponseCode();

                if (responseCode == 200) {
                    plugin.getLogger().info("✅ " + playerName + " Access Token 유효함");
                    return true;
                } else if (responseCode == 401) {
                    plugin.getLogger().warning("⚠️ " + playerName + " Access Token 만료됨 (401)");
                    return false;
                } else {
                    plugin.getLogger().warning("⚠️ " + playerName + " Access Token 검증 실패 (" + responseCode + ")");
                    return false;
                }

            } catch (Exception e) {
                plugin.getLogger().warning("⚠️ " + playerName + " Access Token 검증 오류: " + e.getMessage());
                return false;
            }
        });
    }

    /**
     * 토큰 갱신 후 Socket.IO 재연결
     */
    private void reconnectWithRefreshToken(String playerName, UserAuth userAuth) {
        plugin.getLogger().info("🔄 " + playerName + " 토큰 갱신 및 재연결 프로세스 시작"); // 추가된 로그

        new BukkitRunnable() {
            @Override
            public void run() {
                // 1. Access Token 유효성 검사 (기존 코드)
                validateAccessToken(playerName, userAuth.accessToken).thenAccept(isValid -> {
                    if (isValid) {
                        // 토큰이 유효하면 바로 Socket.IO 연결
                        plugin.getLogger().info("✅ " + playerName + " 기존 토큰으로 재연결");
                        connectToSocketIO(playerName, userAuth);

                        // 성공 알림 추가
                        new BukkitRunnable() {
                            @Override
                            public void run() {
                                Player player = Bukkit.getPlayer(playerName);
                                if (player != null) {
                                    player.sendMessage(ChatColor.GREEN + "🔗 치지직 자동 재연결 성공!");
                                }
                            }
                        }.runTask(plugin);

                    } else {
                        // 토큰이 무효하면 갱신 시도 (기존 코드)
                        plugin.getLogger().info("🔄 " + playerName + " 토큰 갱신 후 재연결 시도");

                        refreshAccessToken(playerName, userAuth).thenAccept(refreshSuccess -> {
                            if (refreshSuccess) {
                                // 갱신 성공 시 새 토큰으로 Socket.IO 연결 (기존 코드)
                                UserAuth newUserAuth = userTokens.get(playerName);
                                if (newUserAuth != null) {
                                    connectToSocketIO(playerName, newUserAuth);

                                    // 성공 알림 추가
                                    new BukkitRunnable() {
                                        @Override
                                        public void run() {
                                            Player player = Bukkit.getPlayer(playerName);
                                            if (player != null) {
                                                player.sendMessage(ChatColor.GREEN + "🔄 치지직 토큰 자동 갱신 및 재연결 성공!");
                                            }
                                        }
                                    }.runTask(plugin);
                                }
                            } else {
                                // 갱신 실패 시 재인증 안내 (기존 코드에 로그만 추가)
                                plugin.getLogger().severe("❌ " + playerName + " 토큰 갱신 실패 - 재인증 필요");

                                new BukkitRunnable() {
                                    @Override
                                    public void run() {
                                        Player player = Bukkit.getPlayer(playerName);
                                        if (player != null) {
                                            player.sendMessage(ChatColor.RED + "❌ 치지직 연결 복구 실패!");
                                            player.sendMessage(ChatColor.YELLOW + "'/치즈설정 연결'로 다시 연결해주세요.");
                                        }
                                    }
                                }.runTask(plugin);
                            }
                        });
                    }
                });
            }
        }.runTaskAsynchronously(plugin);
    }

    /**
     * 토큰 상태 확인 (플레이어용)
     */
    public CompletableFuture<String> checkTokenStatus(String playerName) {
        return CompletableFuture.supplyAsync(() -> {
            UserAuth userAuth = userTokens.get(playerName);
            if (userAuth == null) {
                return ChatColor.RED + "❌ 연결되지 않음";
            }

            // Access Token 유효성 검사
            try {
                URL url = new URL(apiBaseUrl + "/open/v1/users/me");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();

                conn.setRequestMethod("GET");
                conn.setRequestProperty("Authorization", "Bearer " + userAuth.accessToken);
                conn.setRequestProperty("Accept", "application/json");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(10000);

                int responseCode = conn.getResponseCode();

                if (responseCode == 200) {
                    return ChatColor.GREEN + "✅ 토큰 유효";
                } else if (responseCode == 401) {
                    return ChatColor.YELLOW + "⚠️ 토큰 만료 (자동 갱신 시도 가능)";
                } else {
                    return ChatColor.RED + "❌ 토큰 오류 (" + responseCode + ")";
                }

            } catch (Exception e) {
                return ChatColor.RED + "❌ 토큰 검증 실패: " + e.getMessage();
            }
        });
    }

    /**
     * 서버 시작 시 모든 저장된 토큰 검증 및 갱신 (오프라인 상태에서도 실행)
     */
    private void validateAndRefreshAllTokens() {
        if (userTokens.isEmpty()) {
            plugin.getLogger().info("📋 저장된 치지직 연동 사용자가 없습니다.");
            return;
        }

        plugin.getLogger().info("🔄 " + userTokens.size() + "명의 저장된 토큰 검증 및 갱신 시작...");

        // 백그라운드에서 모든 토큰 처리
        new BukkitRunnable() {
            @Override
            public void run() {
                // final 배열을 사용하여 카운터 관리
                final int[] counts = {0, 0, 0}; // validCount, refreshedCount, expiredCount

                for (Map.Entry<String, UserAuth> entry : new HashMap<>(userTokens).entrySet()) {
                    String playerName = entry.getKey();
                    UserAuth userAuth = entry.getValue();

                    try {
                        // 1. 토큰 유효성 검사 (동기 방식으로 변경)
                        boolean isValid = validateAccessTokenSync(playerName, userAuth.accessToken);

                        if (isValid) {
                            counts[0]++; // validCount++
                            plugin.getLogger().info("✅ " + playerName + " 토큰 유효 - 접속 시 자동 연결됩니다");
                        } else {
                            // 2. 토큰 갱신 시도 (동기 방식)
                            boolean refreshSuccess = refreshAccessTokenSync(playerName, userAuth);

                            if (refreshSuccess) {
                                counts[1]++; // refreshedCount++
                                plugin.getLogger().info("🔄 " + playerName + " 토큰 자동 갱신 완료 - 접속 시 연결됩니다");
                            } else {
                                counts[2]++; // expiredCount++
                                plugin.getLogger().warning("❌ " + playerName + " 토큰 만료 - 재인증 필요");
                                // 토큰 제거하지 않고 보존 (수동 재연결 기회 제공)
                            }
                        }

                        // API 호출 간격 (과부하 방지)
                        Thread.sleep(1000);

                    } catch (Exception e) {
                        plugin.getLogger().warning("⚠️ " + playerName + " 토큰 처리 중 오류: " + e.getMessage());
                    }
                }

                // 메인 스레드에서 결과 로그
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        plugin.getLogger().info("📊 토큰 검증 완료 - 유효:" + counts[0] +
                                ", 갱신:" + counts[1] +
                                ", 만료:" + counts[2]);
                        plugin.getLogger().info("✅ 모든 사용자가 접속 시 자동으로 치지직에 연결됩니다!");
                    }
                }.runTask(plugin);
            }
        }.runTaskAsynchronously(plugin);
    }

    /**
     * 강제 재연결 (예외 처리 추가)
     */
    public void forceReconnect(String playerName) {
        UserAuth userAuth = userTokens.get(playerName);
        if (userAuth == null) {
            plugin.getLogger().warning("❌ " + playerName + " 토큰을 찾을 수 없음");
            return;
        }

        plugin.getLogger().info("🔄 " + playerName + " 강제 재연결 시작...");

        try {
            // 1. 기존 연결 완전히 정리
            Socket existingSocket = activeSockets.remove(playerName);
            if (existingSocket != null) {
                try {
                    existingSocket.disconnect();
                    existingSocket.close();
                    plugin.getLogger().info("🧹 " + playerName + " 기존 Socket 정리 완료");
                } catch (Exception e) {
                    plugin.getLogger().warning("⚠️ " + playerName + " Socket 정리 중 오류: " + e.getMessage());
                }
            }

            // 2. 세션 키 초기화 (새로운 세션으로 시작)
            sessionKeys.remove(playerName);
            plugin.getLogger().info("🔄 " + playerName + " 세션 키 초기화 완료");

            // 3. 새로운 세션 생성 후 재연결
            new BukkitRunnable() {
                @Override
                public void run() {
                    try {
                        JsonObject sessionResponse = createSocketSession(userAuth.accessToken);

                        if (sessionResponse != null && sessionResponse.has("url")) {
                            String newSessionUrl = sessionResponse.getAsJsonPrimitive("url").getAsString();
                            UserAuth newUserAuth = new UserAuth(userAuth.accessToken, userAuth.refreshToken, playerName, newSessionUrl);
                            userTokens.put(playerName, newUserAuth);
                            saveUserTokens();

                            plugin.getLogger().info("✅ " + playerName + " 새로운 세션 생성 완료");

                            // 메인 스레드에서 재연결
                            new BukkitRunnable() {
                                @Override
                                public void run() {
                                    try {
                                        connectToSocketIO(playerName, newUserAuth);

                                        Player player = Bukkit.getPlayer(playerName);
                                        if (player != null) {
                                            player.sendMessage(ChatColor.GREEN + "🔄 치지직 강제 재연결 완료!");
                                            player.sendMessage(ChatColor.AQUA + "새로운 세션으로 연결을 시도합니다...");
                                        }
                                    } catch (Exception e) {
                                        plugin.getLogger().severe("❌ " + playerName + " Socket 연결 중 오류: " + e.getMessage());
                                        e.printStackTrace();
                                    }
                                }
                            }.runTask(plugin);

                        } else {
                            plugin.getLogger().severe("❌ " + playerName + " 새로운 세션 생성 실패 - 응답이 null이거나 url이 없음");

                            new BukkitRunnable() {
                                @Override
                                public void run() {
                                    Player player = Bukkit.getPlayer(playerName);
                                    if (player != null) {
                                        player.sendMessage(ChatColor.RED + "❌ 강제 재연결 실패!");
                                        player.sendMessage(ChatColor.YELLOW + "'/치즈설정 해제' 후 '/치즈설정 연결'을 시도하세요.");
                                    }
                                }
                            }.runTask(plugin);
                        }
                    } catch (Exception e) {
                        plugin.getLogger().severe("❌ " + playerName + " 세션 생성 중 예외: " + e.getMessage());
                        e.printStackTrace();

                        new BukkitRunnable() {
                            @Override
                            public void run() {
                                Player player = Bukkit.getPlayer(playerName);
                                if (player != null) {
                                    player.sendMessage(ChatColor.RED + "❌ 세션 생성 중 오류 발생!");
                                    player.sendMessage(ChatColor.YELLOW + "콘솔 로그를 확인하세요.");
                                }
                            }
                        }.runTask(plugin);
                    }
                }
            }.runTaskAsynchronously(plugin);

        } catch (Exception e) {
            plugin.getLogger().severe("❌ " + playerName + " 강제 재연결 중 예외: " + e.getMessage());
            e.printStackTrace();

            // 플레이어에게 오류 알림
            Player player = Bukkit.getPlayer(playerName);
            if (player != null) {
                player.sendMessage(ChatColor.RED + "❌ 강제 재연결 중 오류 발생!");
                player.sendMessage(ChatColor.GRAY + "오류: " + e.getMessage());
            }
        }
    }

    /**
     * 동기식 토큰 유효성 검사 (백그라운드용)
     */
    private boolean validateAccessTokenSync(String playerName, String accessToken) {
        try {
            URL url = new URL(apiBaseUrl + "/open/v1/users/me");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();

            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);

            int responseCode = conn.getResponseCode();
            return responseCode == 200;

        } catch (Exception e) {
            plugin.getLogger().warning("토큰 검증 오류: " + playerName + " - " + e.getMessage());
            return false;
        }
    }

    /**
     * 동기식 토큰 갱신 (백그라운드용)
     */
    private boolean refreshAccessTokenSync(String playerName, UserAuth userAuth) {
        try {
            URL url = new URL(authBaseUrl + "/auth/v1/token");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();

            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);

            JsonObject requestJson = new JsonObject();
            requestJson.addProperty("grantType", "refresh_token");
            requestJson.addProperty("clientId", clientId);
            requestJson.addProperty("clientSecret", clientSecret);
            requestJson.addProperty("refreshToken", userAuth.refreshToken);

            String jsonData = gson.toJson(requestJson);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonData.getBytes("UTF-8"));
                os.flush();
            }

            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                String response = readResponse(conn);
                JsonObject json = JsonParser.parseString(response).getAsJsonObject();
                JsonObject content = json.getAsJsonObject("content");

                String newAccessToken = content.get("accessToken").getAsString();
                String newRefreshToken = content.get("refreshToken").getAsString();

                // 새 토큰으로 업데이트
                UserAuth newUserAuth = new UserAuth(newAccessToken, newRefreshToken, playerName, userAuth.sessionUrl);
                userTokens.put(playerName, newUserAuth);
                saveUserTokens();

                return true;
            }

        } catch (Exception e) {
            plugin.getLogger().warning("토큰 갱신 오류: " + playerName + " - " + e.getMessage());
        }

        return false;
    }

    /**
     * 주기적 토큰 유지보수 시스템 시작
     */
    private void startTokenMaintenance() {
        if (tokenMaintenanceTask != null) {
            tokenMaintenanceTask.cancel();
        }

        tokenMaintenanceTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (userTokens.isEmpty()) {
                    return;
                }

                plugin.getLogger().info("🔧 주기적 토큰 상태 확인 시작...");

                // 비동기로 토큰 상태 확인
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        for (Map.Entry<String, UserAuth> entry : new HashMap<>(userTokens).entrySet()) {
                            String playerName = entry.getKey();
                            UserAuth userAuth = entry.getValue();

                            // 토큰 만료 30분 전에 미리 갱신
                            if (shouldRefreshToken(userAuth)) {
                                plugin.getLogger().info("🔄 " + playerName + " 토큰 예방적 갱신 시도");
                                refreshAccessTokenSync(playerName, userAuth);
                            }

                            try {
                                Thread.sleep(2000); // 2초 간격
                            } catch (InterruptedException e) {
                                break;
                            }
                        }
                    }
                }.runTaskAsynchronously(plugin);
            }
        };

        // 6시간마다 실행 (21600틱 = 6시간)
        tokenMaintenanceTask.runTaskTimer(plugin, 6000L, 432000L);
        plugin.getLogger().info("⏰ 토큰 자동 유지보수 시스템 시작 (6시간 주기)");
    }

    /**
     * 토큰 갱신이 필요한지 판단 (실제로는 더 정교한 로직 필요)
     */
    private boolean shouldRefreshToken(UserAuth userAuth) {
        // 간단한 구현: 항상 검증해서 만료되면 갱신
        // 실제로는 토큰에 만료 시간이 포함되어 있다면 그것을 활용
        return true;
    }

}
