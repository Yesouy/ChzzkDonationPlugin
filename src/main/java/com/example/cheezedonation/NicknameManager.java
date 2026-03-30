// NicknameManager.java - 모히스트 서버 전용 안전 버전
package com.example.cheezedonation;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 한글 닉네임 관리 시스템 - 모히스트 서버 전용 안전 버전
 * 모히스트 서버의 호환성 문제를 해결하기 위해 최소한의 안전한 API만 사용
 */
public class NicknameManager implements Listener {

    private final JavaPlugin plugin;
    private final Gson gson;
    private final Map<UUID, String> playerNicknames = new ConcurrentHashMap<>();
    private final Map<String, UUID> nicknameToUUID = new ConcurrentHashMap<>();

    // 닉네임 검증 패턴 (더 안전하게)
    private final Pattern koreanPattern = Pattern.compile("^[가-힣a-zA-Z0-9_]{2,16}$");

    public NicknameManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.gson = new Gson();

        // 모히스트 서버 감지 로그
        plugin.getLogger().info("🔧 모히스트 서버용 닉네임 시스템 초기화 중...");

        // 이벤트 리스너 등록 (안전하게)
        try {
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
            plugin.getLogger().info("✅ 이벤트 리스너 등록 완료");
        } catch (Exception e) {
            plugin.getLogger().severe("❌ 이벤트 리스너 등록 실패: " + e.getMessage());
        }

        // 닉네임 데이터 로드
        loadNicknames();
    }

    /**
     * 플레이어 닉네임 설정 (모히스트 안전 버전)
     */
    public boolean setNickname(Player player, String nickname) {
        if (player == null || !player.isOnline()) {
            return false;
        }

        UUID playerId = player.getUniqueId();

        // 닉네임 유효성 검사
        if (!isValidNickname(nickname)) {
            return false;
        }

        // 중복 닉네임 검사
        if (isNicknameUsed(nickname, playerId)) {
            return false;
        }

        // 기존 닉네임 제거
        String oldNickname = playerNicknames.get(playerId);
        if (oldNickname != null) {
            nicknameToUUID.remove(oldNickname);
        }

        // 새 닉네임 설정
        playerNicknames.put(playerId, nickname);
        nicknameToUUID.put(nickname, playerId);

        // 실시간 닉네임 적용 (안전하게)
        applyNicknameSafely(player, nickname);

        // 저장
        saveNicknames();

        return true;
    }

    /**
     * 플레이어 닉네임 제거
     */
    public void removeNickname(Player player) {
        if (player == null) {
            return;
        }

        UUID playerId = player.getUniqueId();
        String nickname = playerNicknames.remove(playerId);

        if (nickname != null) {
            nicknameToUUID.remove(nickname);

            // 원본 닉네임으로 복구 (안전하게)
            resetNicknameSafely(player);

            // 저장
            saveNicknames();
        }
    }

    /**
     * 플레이어 닉네임 조회
     */
    public String getNickname(Player player) {
        if (player == null) {
            return null;
        }
        return playerNicknames.get(player.getUniqueId());
    }

    /**
     * 표시용 닉네임 조회 (없으면 원본 닉네임)
     */
    public String getDisplayName(Player player) {
        if (player == null) {
            return "Unknown";
        }
        String nickname = playerNicknames.get(player.getUniqueId());
        return nickname != null ? nickname : player.getName();
    }

    /**
     * 닉네임으로 플레이어 찾기
     */
    public Player getPlayerByNickname(String nickname) {
        UUID playerId = nicknameToUUID.get(nickname);
        return playerId != null ? Bukkit.getPlayer(playerId) : null;
    }

    /**
     * 닉네임 유효성 검사 (강화된 버전)
     */
    private boolean isValidNickname(String nickname) {
        if (nickname == null || nickname.trim().isEmpty()) {
            return false;
        }

        // 길이 검사 (2-16자)
        if (nickname.length() < 2 || nickname.length() > 16) {
            return false;
        }

        // 패턴 검사 (한글, 영문, 숫자, 언더스코어만 허용)
        try {
            if (!koreanPattern.matcher(nickname).matches()) {
                return false;
            }
        } catch (Exception e) {
            plugin.getLogger().warning("닉네임 패턴 검사 오류: " + e.getMessage());
            return false;
        }

        // 금지된 단어 검사
        String[] bannedWords = {"admin", "관리자", "운영자", "op", "staff", "서버", "server", "console", "콘솔"};
        String lowerNickname = nickname.toLowerCase();
        for (String banned : bannedWords) {
            if (lowerNickname.contains(banned.toLowerCase())) {
                return false;
            }
        }

        return true;
    }

    /**
     * 닉네임 중복 검사
     */
    private boolean isNicknameUsed(String nickname, UUID excludePlayer) {
        UUID existingPlayer = nicknameToUUID.get(nickname);
        return existingPlayer != null && !existingPlayer.equals(excludePlayer);
    }

    /**
     * 닉네임 안전하게 적용 (모히스트 호환)
     */
    private void applyNicknameSafely(Player player, String nickname) {
        if (player == null || !player.isOnline()) {
            return;
        }

        // 메인 스레드에서 안전하게 실행
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    // 색상 코드 처리 (안전하게)
                    String coloredNickname;
                    try {
                        coloredNickname = ChatColor.translateAlternateColorCodes('&', nickname);
                    } catch (Exception e) {
                        coloredNickname = nickname; // 색상 코드 처리 실패 시 원본 사용
                    }

                    // 1. 탭리스트 닉네임 변경 (안전하게)
                    try {
                        player.setPlayerListName(coloredNickname);
                    } catch (Exception e) {
                        plugin.getLogger().warning("탭리스트 닉네임 설정 실패: " + e.getMessage());
                    }

                    // 2. 표시 이름 변경 (안전하게)
                    try {
                        player.setDisplayName(coloredNickname);
                    } catch (Exception e) {
                        plugin.getLogger().warning("표시 이름 설정 실패: " + e.getMessage());
                    }

                    // 3. 커스텀 이름은 비활성화 (스코어보드 팀과 중복 방지)
                    try {
                        player.setCustomNameVisible(false);
                        player.setCustomName(null);

                        plugin.getLogger().info("CustomName 비활성화 완료: " + player.getName());

                    } catch (Exception e) {
                        plugin.getLogger().warning("커스텀 이름 비활성화 실패: " + e.getMessage());
                    }

                    // 4. 스코어보드 팀 시스템 사용 (머리 위 닉네임 전용)
                    try {
                        applyScoreboardTeam(player, coloredNickname);
                    } catch (Exception e) {
                        plugin.getLogger().warning("스코어보드 팀 설정 실패: " + e.getMessage());
                    }

                    plugin.getLogger().info("✅ " + player.getName() + " 닉네임 적용: " + nickname);

                } catch (Exception e) {
                    plugin.getLogger().severe("❌ 닉네임 적용 실패: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }.runTask(plugin);
    }

    // 새로운 메서드 추가 - 스코어보드 팀으로 닉네임 적용
    private void applyScoreboardTeam(Player player, String nickname) {
        try {
            // 모든 온라인 플레이어의 스코어보드 설정
            for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                if (onlinePlayer != null && onlinePlayer.isOnline()) {
                    org.bukkit.scoreboard.Scoreboard board = onlinePlayer.getScoreboard();
                    if (board == null) {
                        board = Bukkit.getScoreboardManager().getNewScoreboard();
                        onlinePlayer.setScoreboard(board);
                    }

                    String teamName = "nick_" + player.getName();

                    // 기존 팀 제거
                    org.bukkit.scoreboard.Team oldTeam = board.getTeam(teamName);
                    if (oldTeam != null) {
                        oldTeam.unregister();
                    }

                    // 새 팀 생성
                    org.bukkit.scoreboard.Team team = board.registerNewTeam(teamName);

                    // 중요: 원래 이름을 완전히 숨기고 닉네임만 표시
                    team.setPrefix(nickname);
                    team.setSuffix(""); // suffix는 비워두기

                    // 팀 옵션 설정 (이름 태그 숨기기)
                    try {
                        team.setOption(org.bukkit.scoreboard.Team.Option.NAME_TAG_VISIBILITY,
                                org.bukkit.scoreboard.Team.OptionStatus.ALWAYS);
                    } catch (Exception e) {
                        plugin.getLogger().warning("팀 옵션 설정 실패: " + e.getMessage());
                    }

                    // 플레이어를 팀에 추가
                    team.addEntry(player.getName());

                    plugin.getLogger().info("스코어보드 팀 적용: " + onlinePlayer.getName() + "에게 " + player.getName() + " -> 닉네임만: " + nickname);
                }
            }

            // 추가: 모든 기존 플레이어들의 닉네임도 새 플레이어에게 적용
            applyAllNicknamesToPlayer(player);

        } catch (Exception e) {
            plugin.getLogger().severe("스코어보드 팀 적용 실패: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // 새로운 메서드 추가 - 모든 기존 플레이어의 닉네임을 새 플레이어에게 적용
    private void applyAllNicknamesToPlayer(Player newPlayer) {
        try {
            org.bukkit.scoreboard.Scoreboard board = newPlayer.getScoreboard();
            if (board == null) {
                board = Bukkit.getScoreboardManager().getNewScoreboard();
                newPlayer.setScoreboard(board);
            }

            // 모든 온라인 플레이어의 닉네임을 새 플레이어에게 적용
            for (Player existingPlayer : Bukkit.getOnlinePlayers()) {
                if (existingPlayer != null && existingPlayer != newPlayer && existingPlayer.isOnline()) {
                    String existingNickname = playerNicknames.get(existingPlayer.getUniqueId());
                    if (existingNickname != null) {
                        String coloredNickname = ChatColor.translateAlternateColorCodes('&', existingNickname);

                        String teamName = "nick_" + existingPlayer.getName();

                        // 기존 팀 제거
                        org.bukkit.scoreboard.Team oldTeam = board.getTeam(teamName);
                        if (oldTeam != null) {
                            oldTeam.unregister();
                        }

                        // 새 팀 생성
                        org.bukkit.scoreboard.Team team = board.registerNewTeam(teamName);
                        team.setPrefix(coloredNickname);
                        team.setSuffix("");

                        try {
                            team.setOption(org.bukkit.scoreboard.Team.Option.NAME_TAG_VISIBILITY,
                                    org.bukkit.scoreboard.Team.OptionStatus.ALWAYS);
                        } catch (Exception e) {
                            // 옵션 설정 실패해도 계속 진행
                        }

                        team.addEntry(existingPlayer.getName());

                        plugin.getLogger().info("기존 플레이어 닉네임 적용: " + newPlayer.getName() + "에게 " + existingPlayer.getName() + " -> " + coloredNickname);
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().severe("기존 플레이어 닉네임 적용 실패: " + e.getMessage());
        }
    }

    // 더 강력한 머리 위 닉네임 적용 메서드
    public void forceApplyNameTag(Player player, String nickname) {
        if (player == null || !player.isOnline()) {
            return;
        }

        new BukkitRunnable() {
            int attempts = 0;
            final int maxAttempts = 5;

            @Override
            public void run() {
                if (!player.isOnline() || attempts >= maxAttempts) {
                    this.cancel();
                    return;
                }

                attempts++;

                try {
                    String coloredNickname = ChatColor.translateAlternateColorCodes('&', nickname);

                    // CustomName 비활성화 (중복 방지)
                    player.setCustomNameVisible(false);
                    player.setCustomName(null);

                    // 스코어보드 팀으로만 처리
                    applyScoreboardTeam(player, coloredNickname);

                    plugin.getLogger().info("강제 적용 시도 " + attempts + ": " + player.getName() + " -> " + coloredNickname);

                } catch (Exception e) {
                    plugin.getLogger().warning("강제 네임태그 적용 실패 (시도 " + attempts + "): " + e.getMessage());
                }
            }
        }.runTaskTimer(plugin, 20L, 40L); // 1초 후 시작, 2초마다 재시도
    }

    /**
     * 원본 닉네임으로 안전하게 복구
     */
    private void resetNicknameSafely(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    String originalName = player.getName();

                    // 1. 탭리스트 닉네임 복구 (안전하게)
                    try {
                        player.setPlayerListName(originalName);
                    } catch (Exception e) {
                        plugin.getLogger().warning("탭리스트 닉네임 복구 실패: " + e.getMessage());
                    }

                    // 2. 표시 이름 복구 (안전하게)
                    try {
                        player.setDisplayName(originalName);
                    } catch (Exception e) {
                        plugin.getLogger().warning("표시 이름 복구 실패: " + e.getMessage());
                    }

                    // 3. 커스텀 이름 복구 (안전하게)
                    try {
                        player.setCustomName(originalName);
                        player.setCustomNameVisible(false);
                    } catch (Exception e) {
                        plugin.getLogger().warning("커스텀 이름 복구 실패 (모히스트 호환성 문제): " + e.getMessage());
                    }

                    plugin.getLogger().info("🔄 " + originalName + " 닉네임 복구");

                } catch (Exception e) {
                    plugin.getLogger().severe("❌ 닉네임 복구 실패: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }.runTask(plugin);
    }

    /**
     * 플레이어 접속 시 닉네임 적용 (모히스트 안전 버전)
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }

        String nickname = playerNicknames.get(player.getUniqueId());

        if (nickname != null) {
            plugin.getLogger().info("플레이어 접속 - 닉네임 적용 시작: " + player.getName() + " -> " + nickname);

            // 접속 메시지를 노란색으로 완전히 새로 만들기
            String joinMessage = event.getJoinMessage();
            if (joinMessage != null) {
                String coloredNickname = ChatColor.translateAlternateColorCodes('&', nickname);

                // 노란색 접속 메시지 생성
                String newJoinMessage;
                if (joinMessage.contains("joined the game")) {
                    newJoinMessage = ChatColor.YELLOW + coloredNickname + " joined the game";
                } else if (joinMessage.contains("게임에 참가")) {
                    newJoinMessage = ChatColor.YELLOW + coloredNickname + " 게임에 참가했습니다";
                } else {
                    // 일반적인 패턴으로 새로 만들기
                    newJoinMessage = ChatColor.YELLOW + coloredNickname + " joined the game";
                }

                event.setJoinMessage(newJoinMessage);
                plugin.getLogger().info("접속 메시지 완전 교체: '" + joinMessage + "' -> '" + newJoinMessage + "'");
            }

            // 여러 단계로 닉네임 적용
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (player.isOnline()) {
                        applyNicknameSafely(player, nickname);
                    }
                }
            }.runTaskLater(plugin, 20L);

            new BukkitRunnable() {
                @Override
                public void run() {
                    if (player.isOnline()) {
                        forceApplyNameTag(player, nickname);
                    }
                }
            }.runTaskLater(plugin, 60L);
        }
    }

    // 퇴장 메시지 처리
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }

        String nickname = playerNicknames.get(player.getUniqueId());
        if (nickname != null) {
            String coloredNickname = ChatColor.translateAlternateColorCodes('&', nickname);

            // 퇴장 메시지도 노란색으로 완전히 새로 만들기
            String quitMessage = event.getQuitMessage();
            if (quitMessage != null) {
                String newQuitMessage;
                if (quitMessage.contains("left the game")) {
                    newQuitMessage = ChatColor.YELLOW + coloredNickname + " left the game";
                } else if (quitMessage.contains("게임을 떠남")) {
                    newQuitMessage = ChatColor.YELLOW + coloredNickname + " 게임을 떠났습니다";
                } else {
                    newQuitMessage = ChatColor.YELLOW + coloredNickname + " left the game";
                }

                event.setQuitMessage(newQuitMessage);
                plugin.getLogger().info("퇴장 메시지 완전 교체: '" + quitMessage + "' -> '" + newQuitMessage + "'");
            }
        }
    }

    // 머리 위 닉네임 강제 새로고침
    private void forceRefreshNameTag(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    // 모든 온라인 플레이어에게 이 플레이어를 잠깐 숨겼다가 다시 보여서 닉네임 새로고침
                    for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                        if (onlinePlayer != null && onlinePlayer.isOnline() && !onlinePlayer.equals(player)) {
                            try {
                                onlinePlayer.hidePlayer(plugin, player);
                                new BukkitRunnable() {
                                    @Override
                                    public void run() {
                                        if (onlinePlayer.isOnline() && player.isOnline()) {
                                            onlinePlayer.showPlayer(plugin, player);
                                        }
                                    }
                                }.runTaskLater(plugin, 1L);
                            } catch (Exception e) {
                                // 실패해도 계속 진행
                            }
                        }
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("머리 위 닉네임 새로고침 실패: " + e.getMessage());
                }
            }
        }.runTaskLater(plugin, 20L); // 1초 후 실행
    }

    /**
     * 채팅 이벤트 처리 (모히스트 안전 버전)
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        if (event.isCancelled()) {
            return;
        }

        Player player = event.getPlayer();
        if (player == null) {
            return;
        }

        String nickname = playerNicknames.get(player.getUniqueId());

        if (nickname != null) {
            try {
                String originalFormat = event.getFormat();

                // 색상 코드 처리 (안전하게)
                String coloredNickname;
                try {
                    coloredNickname = ChatColor.translateAlternateColorCodes('&', nickname);
                } catch (Exception e) {
                    coloredNickname = nickname;
                }

                // 플레이어 이름을 닉네임으로 교체
                String newFormat = originalFormat.replace("%1$s", coloredNickname);

                // 안전하게 포맷 설정
                try {
                    event.setFormat(newFormat);
                } catch (Exception e) {
                    plugin.getLogger().warning("채팅 포맷 설정 실패: " + e.getMessage());
                }

            } catch (Exception e) {
                plugin.getLogger().warning("채팅 이벤트 처리 실패: " + e.getMessage());
            }
        }
    }

    /**
     * 닉네임 데이터 저장 (모히스트 안전 버전)
     */
    private void saveNicknames() {
        // 비동기로 저장하여 메인 스레드 블록 방지
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    File dataFolder = plugin.getDataFolder();
                    if (!dataFolder.exists()) {
                        dataFolder.mkdirs();
                    }

                    File file = new File(dataFolder, "nicknames.json");

                    Map<String, String> saveData = new HashMap<>();
                    for (Map.Entry<UUID, String> entry : playerNicknames.entrySet()) {
                        saveData.put(entry.getKey().toString(), entry.getValue());
                    }

                    try (FileWriter writer = new FileWriter(file, StandardCharsets.UTF_8)) {
                        gson.toJson(saveData, writer);
                        writer.flush();
                    }

                    plugin.getLogger().info("💾 닉네임 데이터 저장 완료: " + playerNicknames.size() + "개");

                } catch (Exception e) {
                    plugin.getLogger().severe("❌ 닉네임 저장 오류: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    /**
     * 닉네임 데이터 로드 (모히스트 안전 버전)
     */
    private void loadNicknames() {
        try {
            File file = new File(plugin.getDataFolder(), "nicknames.json");
            if (!file.exists()) {
                plugin.getLogger().info("📂 닉네임 데이터 파일이 없습니다. 새로 생성됩니다.");
                return;
            }

            try (FileReader reader = new FileReader(file, StandardCharsets.UTF_8)) {
                Map<String, String> loadData = gson.fromJson(reader,
                        new TypeToken<Map<String, String>>() {
                        }.getType());

                if (loadData != null) {
                    int loadedCount = 0;
                    for (Map.Entry<String, String> entry : loadData.entrySet()) {
                        try {
                            UUID playerId = UUID.fromString(entry.getKey());
                            String nickname = entry.getValue();

                            if (isValidNickname(nickname)) {
                                playerNicknames.put(playerId, nickname);
                                nicknameToUUID.put(nickname, playerId);
                                loadedCount++;
                            } else {
                                plugin.getLogger().warning("유효하지 않은 닉네임 제외: " + nickname);
                            }
                        } catch (Exception e) {
                            plugin.getLogger().warning("닉네임 로드 중 오류: " + e.getMessage());
                        }
                    }

                    plugin.getLogger().info("📂 닉네임 데이터 로드 완료: " + loadedCount + "개");
                }
            }

        } catch (Exception e) {
            plugin.getLogger().severe("❌ 닉네임 로드 오류: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 모든 플레이어의 닉네임 새로고침 (모히스트 안전 버전)
     */
    public void refreshAllNicknames() {
        new BukkitRunnable() {
            @Override
            public void run() {
                int refreshedCount = 0;
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player != null && player.isOnline()) {
                        String nickname = playerNicknames.get(player.getUniqueId());
                        if (nickname != null) {
                            applyNicknameSafely(player, nickname);
                            refreshedCount++;
                        }
                    }
                }
                plugin.getLogger().info("🔄 " + refreshedCount + "명의 닉네임을 새로고침했습니다.");
            }
        }.runTask(plugin);
    }

    /**
     * 모든 닉네임 데이터 반환 (관리자용)
     */
    public Map<UUID, String> getAllNicknames() {
        return new HashMap<>(playerNicknames);
    }

    /**
     * 닉네임 통계 정보 (모히스트 정보 포함)
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("total_nicknames", playerNicknames.size());
        stats.put("online_with_nicknames",
                Bukkit.getOnlinePlayers().stream()
                        .mapToInt(p -> p != null && playerNicknames.containsKey(p.getUniqueId()) ? 1 : 0)
                        .sum());
        stats.put("server_type", "Mohist");
        stats.put("bukkit_version", Bukkit.getVersion());
        return stats;
    }

    /**
     * 정리 작업 (모히스트 안전 버전)
     */
    public void cleanup() {
        try {
            // 마지막 저장
            saveNicknames();

            // 리소스 정리
            playerNicknames.clear();
            nicknameToUUID.clear();

            plugin.getLogger().info("🧹 닉네임 매니저 정리 완료 (모히스트 호환)");

        } catch (Exception e) {
            plugin.getLogger().severe("❌ 닉네임 매니저 정리 실패: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 모히스트 서버 호환성 체크
     */
    public boolean isMohistCompatible() {
        try {
            // 모히스트 서버 특성 체크
            String version = Bukkit.getVersion();
            return version.toLowerCase().contains("mohist") ||
                    version.toLowerCase().contains("forge");
        } catch (Exception e) {
            return false;
        }
    }
}