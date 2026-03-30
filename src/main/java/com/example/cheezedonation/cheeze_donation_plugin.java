package com.example.cheezedonation;

import org.bukkit.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.meta.ItemMeta;

//import java.text.MessageFormat;
import java.util.Arrays;

import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Firework;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

import com.google.gson.Gson;

public class cheeze_donation_plugin extends JavaPlugin implements Listener {

    // ========== 전역 이벤트 대기열 시스템 ==========
    private final Queue<DonationEvent> globalEventQueue = new LinkedList<>();
    private boolean isProcessingEvent = false;
    private final Object eventLock = new Object();

    private final Map<UUID, Location> prisonedPlayers = new HashMap<>();
    private final Map<UUID, Location> originalLocations = new HashMap<>();
//    private final Set<String> processedDonations = new HashSet<>();

    // 4000원 폭죽 (데미지 없음)
    private final Set<UUID> noDAMAGEFireworks = new HashSet<>();

    // 잭팟 상태 추적을 위한 Map (플레이어별 인벤토리 백업)
    private final Map<UUID, ItemStack[]> jackpotPlayersBackup = new HashMap<>();
    private final Set<UUID> jackpotPlayers = new HashSet<>();
    private final Map<UUID, BukkitRunnable> jackpotTasks = new HashMap<>();
    private final Set<UUID> oneTimeInventorySavePlayers = new HashSet<>();
    private final Map<UUID, Long> inventorySaveStartTime = new HashMap<>();

    // 아이템 사용 쿨다운 관리
    private final Map<UUID, Long> lastItemUseTime = new HashMap<>();
    // 1회용 인벤세이브 사용 중인 개수 추적
    private final Map<UUID, Integer> oneTimeInventorySaveUsageCount = new HashMap<>();
    private final Map<UUID, Map<String, Object>> jackpotPlayersLevelBackup = new HashMap<>();

    // 슬롯머신 상태 관리
    private final Set<UUID> activeSlotMachines = new HashSet<>();
    private final Map<UUID, Queue<Runnable>> slotMachineQueue = new HashMap<>();

    private final Map<UUID, org.bukkit.GameMode> originalGameModes = new HashMap<>();

    // 감옥 시작 시간 추가 (새로 추가)
    private final Map<UUID, Long> prisonStartTime = new HashMap<>();

    // 감옥 변수
    private final Map<UUID, BukkitRunnable> prisonTasks = new HashMap<>();

    //낙사 방지
    private final Set<UUID> loveFallProtection = new HashSet<>();

    // 순회공연 상태 관리
    private final Map<UUID, BukkitRunnable> tourTasks = new HashMap<>();
    private final Map<UUID, Location> tourOriginalLocations = new HashMap<>();
    private final Set<UUID> activeTours = new HashSet<>();
    private final Set<UUID> tourExcludedPlayers = new HashSet<>(); // 순회공연 제외 플레이어 목록

    // chzzk4j 연동 관리자
    private cheezeManager cheezeManager;

    private NicknameManager nicknameManager;

    private Gson gson;

    @Override
    public void onEnable() {
        // 설정 파일 생성
        saveDefaultConfig();

        gson = new Gson();

        // 설정 파일 검증
        if (!validateConfig()) {
            getLogger().severe("❌ 설정 파일이 올바르지 않습니다!");
            getLogger().severe("❌ config.yml에서 chzzk.client-id와 chzzk.client-secret을 확인하세요.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 치직 매니저 초기화
        cheezeManager = new cheezeManager(this, this);

        // 닉네임 매니저 초기화 (새로 추가)
        try {
            nicknameManager = new NicknameManager(this);
            getLogger().info("✅ 한글 닉네임 시스템 초기화 완료 (모히스트 호환)");
        } catch (Exception e) {
            getLogger().severe("❌ 닉네임 시스템 초기화 실패: " + e.getMessage());
            e.printStackTrace();
            // 닉네임 시스템 실패해도 플러그인은 계속 실행
        }

        // 치직 OAuth + Socket.IO 클라이언트 초기화
        cheezeManager.initialize().thenAccept(success -> {
            if (success) {
                getLogger().info("✅ 치직 OAuth + Socket.IO 클라이언트 초기화 성공!");
                getLogger().info("🔗 실시간 치즈연동 시스템 준비 완료!");
                getLogger().info("🚀 이제 실제 치즈를 받아서 연동 할 수 있습니다!");
            } else {
                getLogger().severe("❌ 치직 OAuth + Socket.IO 클라이언트 초기화 실패!");
                getLogger().severe("❌ config.yml 설정을 확인하세요.");
            }
        });

        // 이벤트 리스너 등록
        getServer().getPluginManager().registerEvents(this, this);

        getLogger().info("🔥=== 치직 실시간 치즈 연동 플러그인 활성화 ===🔥");
        getLogger().info("💡 /치즈설정 명령어로 채널 연결 및 테스트를 할 수 있습니다!");
        getLogger().info("🎯 실시간 치즈 연동 이벤트 시스템이 준비되었습니다!");
        getLogger().info("🗣️ 한글 닉네임 시스템이 활성화되었습니다!");
        getLogger().info("💰 치즈 금액별 이벤트:");
        getLogger().info("   1000원:치즈, 4000원:사랑해, 5000원:몹소환, 7000원:잭팟, 9000원:버프/디버프");
        getLogger().info("   , 30000원:텔포, 10000원:순회공연, 100000원:감옥, 500000원:즉사");
    }

    /**
     * 설정 파일 검증 (강화된 버전)
     */
    private boolean validateConfig() {
        String clientId = getConfig().getString("chzzk.client-id", "");
        String clientSecret = getConfig().getString("chzzk.client-secret", "");

        if (clientId.isEmpty() || clientSecret.isEmpty()) {
            getLogger().severe("❌ 치직 Client ID 또는 Client Secret이 설정되지 않았습니다!");
            getLogger().severe("❌ config.yml에서 다음 설정을 확인하세요:");
            getLogger().severe("   chzzk.client-id: 치직 개발자 센터에서 발급받은 Client ID");
            getLogger().severe("   chzzk.client-secret: 치직 개발자 센터에서 발급받은 Client Secret");
            getLogger().severe("📝 치직 개발자 센터: https://developers.chzzk.naver.com");
            return false;
        }

        // Client ID 형식 검증 (UUID 형식)
        if (!clientId.matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")) {
            getLogger().warning("⚠️ Client ID 형식이 올바르지 않을 수 있습니다.");
            getLogger().warning("⚠️ 정상적인 UUID 형식인지 확인하세요: " + clientId);
            getLogger().warning("⚠️ 예시: 944376d1-eb5c-4b0c-9cb0-064400648edb");
        }

        // Client Secret 길이 검증
        if (clientSecret.length() < 30) {
            getLogger().warning("⚠️ Client Secret이 너무 짧습니다. 올바른 Secret인지 확인하세요.");
            getLogger().warning("⚠️ Client Secret 길이: " + clientSecret.length() + " 자");
        }

        // 감옥 위치 검증
        String prisonWorld = getConfig().getString("custom-prison.world", "world");
        if (Bukkit.getWorld(prisonWorld) == null) {
            getLogger().warning("⚠️ 감옥 월드를 찾을 수 없습니다: " + prisonWorld);
            getLogger().warning("⚠️ 감옥 기능이 제대로 작동하지 않을 수 있습니다.");
        }

        getLogger().info("✅ 설정 파일 검증 완료!");
        getLogger().info("🔑 Client ID: " + clientId);
        getLogger().info("🔒 Client Secret: " + clientSecret.substring(0, 10) + "...");
        return true;
    }

    @Override
    public void onDisable() {
        // 닉네임 매니저 정리 (새로 추가 - 맨 앞에)
        if (nicknameManager != null) {
            try {
                nicknameManager.cleanup();
                getLogger().info("✅ 닉네임 시스템 정리 완료");
            } catch (Exception e) {
                getLogger().severe("❌ 닉네임 시스템 정리 실패: " + e.getMessage());
            }
        }

        // chzzk4j 연결 해제
        if (cheezeManager != null) {
            cheezeManager.disconnectAll();
        }

        // 순회공연 태스크들 정리
        for (BukkitRunnable task : tourTasks.values()) {
            if (task != null) {
                task.cancel();
            }
        }
        tourTasks.clear();
        activeTours.clear();
        tourOriginalLocations.clear();
        tourExcludedPlayers.clear();

        // 감옥 태스크들 정리
        for (BukkitRunnable task : prisonTasks.values()) {
            if (task != null) {
                task.cancel();
            }
        }
        prisonTasks.clear();

        // 잭팟 플레이어들의 데이터 정리
        for (BukkitRunnable task : jackpotTasks.values()) {
            if (task != null) {
                task.cancel();
            }
        }
        jackpotTasks.clear();
        jackpotPlayers.clear();
        jackpotPlayersBackup.clear();

        // 슬롯머신 상태 정리
        activeSlotMachines.clear();
        slotMachineQueue.clear();

        // 1회용 인벤세이브 플레이어 정리
        oneTimeInventorySavePlayers.clear();

        // 1회용 인벤세이브 사용 개수 정리
        oneTimeInventorySaveUsageCount.clear();

        // === 낙사 방지 데이터 정리 추가 ===
        loveFallProtection.clear();
        // 인벤세이브 시작 시간 정리
        inventorySaveStartTime.clear();
        // === 레벨 백업 데이터 정리 추가 ===
        jackpotPlayersLevelBackup.clear();

        // 데미지 없는 폭죽 목록 정리
        noDAMAGEFireworks.clear();

        // 대기열 정리
        synchronized (eventLock) {
            globalEventQueue.clear();
            isProcessingEvent = false;
        }

        getLogger().info("🗝️ 전역 이벤트 대기열 정리 완료");

        // 감옥에 있는 플레이어들을 원래 위치와 게임모드로 되돌리기
        for (UUID playerId : prisonedPlayers.keySet()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                if (originalLocations.containsKey(playerId)) {
                    player.teleport(originalLocations.get(playerId));
                }
                if (originalGameModes.containsKey(playerId)) {
                    player.setGameMode(originalGameModes.get(playerId));
                } else {
                    player.setGameMode(org.bukkit.GameMode.SURVIVAL);
                }
            }
        }

        // 감옥 관련 맵 정리
        prisonedPlayers.clear();
        originalLocations.clear();
        originalGameModes.clear();
        prisonStartTime.clear(); // 새로 추가된 맵도 정리

        getLogger().info("🟣🗝️치즈 연동 플러그인이 비활성화되었습니다!🗝️🟣");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("치즈설정")) {
            if (args.length == 0) {
                sender.sendMessage(ChatColor.GOLD + "=== 치직 연동 치즈 플러그인 ===");
                sender.sendMessage(ChatColor.WHITE + "/치즈설정 연결(link) - 내 치직 채널 OAuth 연결");
                sender.sendMessage(ChatColor.WHITE + "/치즈설정 재연결(reconnect) - 내 치직 채널 OAuth 재연결");
                sender.sendMessage(ChatColor.WHITE + "/치즈설정 해제(unlink) - 내 치직 채널 연결 해제");
                sender.sendMessage(ChatColor.WHITE + "/치즈설정 상태(state) - 플러그인 상태");
                sender.sendMessage(ChatColor.WHITE + "/치즈설정 대기열(queue) - 이벤트 대기열 상태 확인");
                sender.sendMessage(ChatColor.WHITE + "/치즈설정 테스트(test) <플레이어> <금액> - 테스트 후원");
                sender.sendMessage(ChatColor.WHITE + "/치즈설정 감옥설정(prisonset) - 현재 위치를 감옥으로 설정");
                sender.sendMessage(ChatColor.WHITE + "/치즈설정 순회제외(tourexclude) <플레이어> - 순회공연에서 제외");
                sender.sendMessage(ChatColor.WHITE + "/치즈설정 순회포함(tourunexclude) <플레이어> - 순회공연 제외 해제");
                sender.sendMessage(ChatColor.WHITE + "/치즈설정 순회목록(tourlist) - 순회공연 제외 목록 확인");

                sender.sendMessage(ChatColor.AQUA + "=== 한글 닉네임 시스템 ===");
                sender.sendMessage(ChatColor.WHITE + "/치즈설정 닉네임변경(nick) <닉네임> - 한글 닉네임 설정");
                sender.sendMessage(ChatColor.WHITE + "/치즈설정 닉네임제거(unnick) - 한글 닉네임 제거");
                sender.sendMessage(ChatColor.WHITE + "/치즈설정 닉네임조회(nickinfo) [플레이어] - 닉네임 정보 조회");
                sender.sendMessage(ChatColor.WHITE + "/치즈설정 닉네임새로고침(refresh) - 모든 닉네임 새로고침");

                if (sender.hasPermission("cheeze.donation.admin")) {
                    sender.sendMessage(ChatColor.LIGHT_PURPLE + "=== 관리자 전용 ===");
                    sender.sendMessage(ChatColor.WHITE + "/치즈설정 닉네임지정(setnick) <플레이어> <닉네임> - 다른 플레이어 닉네임 설정");
                    sender.sendMessage(ChatColor.WHITE + "/치즈설정 닉네임삭제(delnick) <플레이어> - 다른 플레이어 닉네임 제거");
                    sender.sendMessage(ChatColor.WHITE + "/치즈설정 닉네임새로고침(refresh) - 모든 닉네임 새로고침");
                    sender.sendMessage(ChatColor.WHITE + "/치즈설정 닉네임목록(nicklist) - 모든 닉네임 목록 보기");
                    sender.sendMessage(ChatColor.WHITE + "/치즈설정 닉네임디버그(nickdebug) - 닉네임 디버그");
                }

                sender.sendMessage(ChatColor.YELLOW + "후원 이벤트:");
                sender.sendMessage(ChatColor.YELLOW + "1000원:치즈 4000원:사랑해 5000원:몹소환 7000원:인벤보호뽑기 9000원:버프/디버프");
                sender.sendMessage(ChatColor.YELLOW + "10000원:순회공연 30000원:텔포 100000원:감옥 500000원:즉사");

                sender.sendMessage(ChatColor.GREEN + "=== OAuth 연동 방법 ===");
                sender.sendMessage(ChatColor.AQUA + "1. /치즈설정 연결(link) 명령어 실행");
                sender.sendMessage(ChatColor.AQUA + "2. 제공된 링크를 클릭하여 브라우저에서 로그인");
                sender.sendMessage(ChatColor.AQUA + "3. 자동으로 연동 완료!");

                return true;
            }

            String subCommand = args[0].toLowerCase();

            switch (subCommand) {
                case "link":
                case "연결":
                    if (!(sender instanceof Player)) {
                        sender.sendMessage(ChatColor.RED + "이 명령어는 플레이어만 사용할 수 있습니다!");
                        return true;
                    }

                    Player player = (Player) sender;
                    String playerName = player.getName();

                    // 이미 연결된 플레이어인지 확인
                    if (cheezeManager.isPlayerConnected(playerName)) {
                        sender.sendMessage(ChatColor.RED + "❌ 이미 치직에 연결되어 있습니다!");
                        sender.sendMessage(ChatColor.YELLOW + "연결을 해제하려면 '/치즈설정 해제'를 사용하세요.");
                        return true;
                    }

                    sender.sendMessage(ChatColor.YELLOW + "🔗 치직 OAuth 인증을 시작합니다...");

                    String authUrl = cheezeManager.startOAuthFlow(playerName);
                    if (authUrl != null) {
                        sender.sendMessage(ChatColor.GREEN + "✅ 인증 링크가 생성되었습니다!");
                        sender.sendMessage(ChatColor.GOLD + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

                        // 클릭 가능한 링크 생성
                        net.md_5.bungee.api.chat.TextComponent linkComponent = new net.md_5.bungee.api.chat.TextComponent("🔗 여기를 클릭하여 치직 로그인하기");
                        linkComponent.setColor(net.md_5.bungee.api.ChatColor.AQUA);
                        linkComponent.setBold(true);
                        linkComponent.setClickEvent(new net.md_5.bungee.api.chat.ClickEvent(
                                net.md_5.bungee.api.chat.ClickEvent.Action.OPEN_URL, authUrl));
                        linkComponent.setHoverEvent(new net.md_5.bungee.api.chat.HoverEvent(
                                net.md_5.bungee.api.chat.HoverEvent.Action.SHOW_TEXT,
                                new net.md_5.bungee.api.chat.TextComponent[]{
                                        new net.md_5.bungee.api.chat.TextComponent("클릭하면 브라우저에서 치직 로그인 페이지가 열립니다.")
                                }));

                        player.spigot().sendMessage(linkComponent);

                        sender.sendMessage(ChatColor.GOLD + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                        sender.sendMessage(ChatColor.GRAY + "링크를 클릭할 수 없다면 아래 URL을 복사하여 브라우저에서 열어주세요:");
                        sender.sendMessage(ChatColor.WHITE + authUrl);
                        sender.sendMessage(ChatColor.YELLOW + "⚠️ 3분 내에 로그인을 완료해주세요!");
                    } else {
                        sender.sendMessage(ChatColor.RED + "❌ 인증 링크 생성에 실패했습니다!");
                    }
                    break;
                case "unlink":
                case "해제":
                    if (!(sender instanceof Player)) {
                        sender.sendMessage(ChatColor.RED + "이 명령어는 플레이어만 사용할 수 있습니다!");
                        return true;
                    }

                    String disconnectPlayerName = sender.getName();

                    if (!cheezeManager.isPlayerConnected(disconnectPlayerName)) {
                        sender.sendMessage(ChatColor.RED + "❌ 치직에 연결되어 있지 않습니다!");
                        return true;
                    }

                    cheezeManager.disconnectPlayer(disconnectPlayerName)
                            .thenAccept(success -> {
                                if (success) {
                                    sender.sendMessage(ChatColor.GREEN + "✅ 치직 연결 해제 완료!");
                                } else {
                                    sender.sendMessage(ChatColor.RED + "❌ 치직 연결 해제 실패!");
                                }
                            });
                    break;

                case "state":
                case "상태":
                    if (!(sender instanceof Player)) {
                        // 콘솔에서 실행한 경우 - sender를 사용하도록 수정
                        sender.sendMessage(ChatColor.GOLD + "♻=== 플러그인 상태 ===♻");
                        sender.sendMessage(ChatColor.WHITE + "OAuth 서버 상태: " + (cheezeManager != null ? "실행 중" : "중지됨"));
                        sender.sendMessage(ChatColor.WHITE + "감옥에 있는 플레이어: " + prisonedPlayers.size() + "명");
                        sender.sendMessage(ChatColor.WHITE + "인벤세이브 활성화: " + jackpotPlayers.size() + "명");
                        sender.sendMessage(ChatColor.WHITE + "1회용 인벤세이브: " + oneTimeInventorySavePlayers.size() + "명");

                        // 치지직 연동 상태 정보 추가 - sender를 사용하도록 수정
                        if (cheezeManager != null) {
                            Set<String> connectedPlayers = cheezeManager.getConnectedPlayers();
                            int totalOnline = Bukkit.getOnlinePlayers().size();
                            int connectedOnline = 0;

                            for (Player p : Bukkit.getOnlinePlayers()) {
                                if (connectedPlayers.contains(p.getName())) {
                                    connectedOnline++;
                                }
                            }

                            sender.sendMessage(ChatColor.AQUA + "🔗 === 치지직 연동 상태 ===");
                            sender.sendMessage(ChatColor.WHITE + "전체 온라인: " + ChatColor.YELLOW + totalOnline + "명");
                            sender.sendMessage(ChatColor.WHITE + "치지직 연동: " + ChatColor.GREEN + connectedOnline + "명");
                            sender.sendMessage(ChatColor.WHITE + "미연동: " + ChatColor.RED + (totalOnline - connectedOnline) + "명");

                            if (connectedOnline > 0) {
                                sender.sendMessage(ChatColor.GREEN + "✅ 순회공연/텔레포트 대상: " + connectedOnline + "명");
                            } else {
                                sender.sendMessage(ChatColor.RED + "❌ 순회공연/텔레포트 대상이 없습니다!");
                            }

                            // 콘솔용 연결 상태 출력
                            cheezeManager.printConnectionStatus();
                        }
                        return true;
                    }

                    // 플레이어가 실행한 경우
                    Player statusPlayer = (Player) sender;
                    statusPlayer.sendMessage(ChatColor.GOLD + "♻=== 플러그인 상태 ===♻");
                    statusPlayer.sendMessage(ChatColor.WHITE + "OAuth 서버 상태: " + (cheezeManager != null ? ChatColor.GREEN + "실행 중" : ChatColor.RED + "중지됨"));
                    statusPlayer.sendMessage(ChatColor.WHITE + "감옥에 있는 플레이어: " + ChatColor.YELLOW + prisonedPlayers.size() + "명");
                    statusPlayer.sendMessage(ChatColor.WHITE + "인벤세이브 활성화: " + ChatColor.YELLOW + jackpotPlayers.size() + "명");
                    statusPlayer.sendMessage(ChatColor.WHITE + "1회용 인벤세이브: " + ChatColor.YELLOW + oneTimeInventorySavePlayers.size() + "명");

                    if (cheezeManager != null) {
                        cheezeManager.printConnectionStatusToPlayer(statusPlayer);

                        // 토큰 유효성 검사 (비동기)
                        String statusPlayerName = statusPlayer.getName();
                        cheezeManager.checkTokenStatus(statusPlayerName).thenAccept(status -> {
                            new BukkitRunnable() {
                                @Override
                                public void run() {
                                    if (statusPlayer.isOnline()) {
                                        statusPlayer.sendMessage(ChatColor.GOLD + "🔑 토큰 상태: " + status);
                                    }
                                }
                            }.runTask(cheeze_donation_plugin.this);
                        });
                    }

                    // 1회용 인벤세이브 상세 정보 추가
                    UUID statusPlayerId = statusPlayer.getUniqueId();
                    if (oneTimeInventorySavePlayers.contains(statusPlayerId)) {
                        int activatedCount = oneTimeInventorySaveUsageCount.getOrDefault(statusPlayerId, 0);
                        int inventoryCount = getOneTimeInventorySaveItemCount(statusPlayer);
                        statusPlayer.sendMessage(ChatColor.LIGHT_PURPLE + "💎 1회용 인벤세이브 상세:");
                        statusPlayer.sendMessage(ChatColor.YELLOW + "  활성화된 것: " + activatedCount + "회");
                        statusPlayer.sendMessage(ChatColor.YELLOW + "  인벤토리에 있는 것: " + inventoryCount + "개");
                        statusPlayer.sendMessage(ChatColor.GRAY + "  우선순위: 활성화된 것 → 인벤토리에 있는 것");
                    } else {
                        int inventoryCount = getOneTimeInventorySaveItemCount(statusPlayer);
                        if (inventoryCount > 0) {
                            statusPlayer.sendMessage(ChatColor.GRAY + "💎 1회용 인벤세이브권: " + inventoryCount + "개 (비활성화)");
                        }
                    }
                    break;

                case "test":
                case "테스트":
                    if (args.length < 3) {
                        sender.sendMessage(ChatColor.RED + "사용법: /치즈설정 테스트 <플레이어> <금액>");
                        return true;
                    }

                    String testPlayerName = args[1];
                    Player testTargetPlayer = Bukkit.getPlayer(testPlayerName);

                    if (testTargetPlayer == null) {
                        sender.sendMessage(ChatColor.RED + "❌ 플레이어를 찾을 수 없습니다: " + testPlayerName);
                        return true;
                    }

                    try {
                        int amount = Integer.parseInt(args[2]);
                        processDonationEvent(testTargetPlayer, amount, "후원자", "채널명");
                        sender.sendMessage(ChatColor.GREEN + "✅ 테스트 후원 실행: " + testPlayerName + " - " + amount + "원");
                    } catch (NumberFormatException e) {
                        sender.sendMessage(ChatColor.RED + "❌ 올바른 숫자를 입력해주세요!");
                    }
                    break;

                case "prisonset":
                case "감옥설정":
                    if (!(sender instanceof Player)) {
                        sender.sendMessage(ChatColor.RED + "이 명령어는 플레이어만 사용할 수 있습니다!");
                        return true;
                    }

                    Player prisonPlayer = (Player) sender;
                    Location loc = prisonPlayer.getLocation();

                    getConfig().set("custom-prison.world", loc.getWorld().getName());
                    getConfig().set("custom-prison.x", loc.getX());
                    getConfig().set("custom-prison.y", loc.getY());
                    getConfig().set("custom-prison.z", loc.getZ());
                    saveConfig();

                    sender.sendMessage(ChatColor.GREEN + "✅ 감옥 위치가 설정되었습니다!");
                    sender.sendMessage(ChatColor.YELLOW + "좌표: " + (int) loc.getX() + ", " + (int) loc.getY() + ", " + (int) loc.getZ());
                    break;

                case "reconnect":
                case "재연결":
                    if (!(sender instanceof Player)) {
                        sender.sendMessage(ChatColor.RED + "이 명령어는 플레이어만 사용할 수 있습니다!");
                        return true;
                    }

                    Player reconnectPlayer = (Player) sender;
                    String reconnectPlayerName = reconnectPlayer.getName();

                    if (!cheezeManager.isPlayerConnected(reconnectPlayerName)) {
                        sender.sendMessage(ChatColor.RED + "❌ 치지직에 연결되어 있지 않습니다!");
                        sender.sendMessage(ChatColor.YELLOW + "'/치즈설정 연결'로 먼저 연동하세요.");
                        return true;
                    }

                    sender.sendMessage(ChatColor.YELLOW + "🔄 치지직 강제 재연결을 시도합니다...");

                    // 강제 재연결
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            cheezeManager.forceReconnect(reconnectPlayerName);
                        }
                    }.runTaskLater(this, 20L);
                    break;

                case "tourexclude":
                case "순회제외":
                    return handleTourExclude(sender, args);

                case "tourunexclude":
                case "순회포함":
                    return handleTourUnexclude(sender, args);

                case "tourlist":
                case "순회목록":
                    return handleTourExcludeList(sender);

                case "queue":
                case "대기열":
                    synchronized (eventLock) {
                        sender.sendMessage(ChatColor.GOLD + "📋 === 이벤트 대기열 상태 ===");
                        sender.sendMessage(ChatColor.WHITE + "현재 처리 중: " + (isProcessingEvent ? ChatColor.YELLOW + "예" : ChatColor.GREEN + "아니오"));
                        sender.sendMessage(ChatColor.WHITE + "대기열 크기: " + ChatColor.YELLOW + globalEventQueue.size() + "개");
                        sender.sendMessage(ChatColor.AQUA + "⚡ 즉시 처리 이벤트: 1000원, 5000원, 9000원, 30000원, 100000원, 500000원");
                        sender.sendMessage(ChatColor.YELLOW + "🕐 대기열 처리 이벤트: 4000원, 7000원, 10000원");

                        if (!globalEventQueue.isEmpty()) {
                            sender.sendMessage(ChatColor.AQUA + "=== 대기 중인 이벤트 ===");
                            int count = 1;
                            for (DonationEvent event : globalEventQueue) {
                                sender.sendMessage(ChatColor.WHITE + String.valueOf(count) + ". " + event.donorName + " (" + event.amount + "원) - " + event.player.getName());
                                count++;
                                if (count > 5) {
                                    sender.sendMessage(ChatColor.GRAY + "... 및 " + (globalEventQueue.size() - 5) + "개 더");
                                    break;
                                }
                            }
                        }
                    }
                    break;

                case "nick":
                case "닉네임변경":
                    return handleNicknameChange(sender, args);

                case "unnick":
                case "닉네임제거":
                    return handleNicknameRemove(sender);

                case "nickinfo":
                case "닉네임조회":
                    return handleNicknameInfo(sender, args);

                case "setnick":
                case "닉네임지정":
                    return handleAdminSetNickname(sender, args);

                case "delnick":
                case "닉네임삭제":
                    return handleAdminRemoveNickname(sender, args);

                case "nicklist":
                case "닉네임목록":
                    return handleNicknameList(sender);

                case "nickdebug":
                case "닉네임디버그":
                    if (!(sender instanceof Player)) {
                        sender.sendMessage(ChatColor.RED + "이 명령어는 플레이어만 사용할 수 있습니다!");
                        return true;
                    }

                    Player debugPlayer = (Player) sender;
                    if (nicknameManager != null) {
                        String nickname = nicknameManager.getNickname(debugPlayer);
                        sender.sendMessage(ChatColor.YELLOW + "=== 닉네임 디버그 정보 ===");
                        sender.sendMessage("설정된 닉네임: " + (nickname != null ? nickname : "없음"));
                        sender.sendMessage("CustomName: " + debugPlayer.getCustomName());
                        sender.sendMessage("CustomNameVisible: " + debugPlayer.isCustomNameVisible());
                        sender.sendMessage("DisplayName: " + debugPlayer.getDisplayName());
                        sender.sendMessage("PlayerListName: " + debugPlayer.getPlayerListName());

                        if (nickname != null) {
                            sender.sendMessage(ChatColor.GREEN + "강제 닉네임 재적용을 시도합니다...");
                            nicknameManager.forceApplyNameTag(debugPlayer, nickname);
                        }
                    }
                    break;
                default:
                    sender.sendMessage(ChatColor.RED + "알 수 없는 명령어입니다. /치즈설정 으로 도움말을 확인하세요.");
                    break;
            }

            return true;
        }

        return false;
    }

    /**
     * 후원 이벤트 클래스
     */
    public static class DonationEvent {
        public final Player player;
        public final int amount;
        public final String donorName;
        public final String streamerId;
        public final long timestamp;

        public DonationEvent(Player player, int amount, String donorName, String streamerId) {
            this.player = player;
            this.amount = amount;
            this.donorName = donorName;
            this.streamerId = streamerId;
            this.timestamp = System.currentTimeMillis();
        }
    }

    public void processDonationEvent(Player player, int amount, String donorName, String streamerId) {
        // 즉시 처리 가능한 이벤트들은 대기열 없이 바로 처리
        if (isInstantEvent(amount)) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    executeInstantEventDirect(player, amount, donorName, streamerId);
                }
            }.runTask(this);
            return; // 대기열에 추가하지 않고 바로 리턴
        }

        // 기존 대기열 로직 (시간이 걸리는 이벤트만)
        synchronized (eventLock) {
            DonationEvent event = new DonationEvent(player, amount, donorName, streamerId);
            globalEventQueue.offer(event);

            getLogger().info("🎯 치즈 이벤트 대기열 추가: " + donorName + " (" + amount + "원) - 대기열 크기: " + globalEventQueue.size());

            if (!isProcessingEvent) {
                processNextEvent();
            }
        }
    }

    /**
     * 즉시 처리 가능한 이벤트인지 확인
     */
    private boolean isInstantEvent(int amount) {
        return amount == 1000 ||   // 치즈 (즉시)
                amount == 5000 ||   // 몹소환 (즉시)
                amount == 9000 ||   // 버프/디버프 (즉시)
                amount == 30000 ||  // 텔레포트 (즉시)
                amount == 100000 || // 감옥 (백그라운드에서 실행되므로 즉시 처리 가능)
                amount == 500000;   // 즉사 (즉시)
    }

    /**
     * 즉시 처리 이벤트 실행 (대기열 없이 바로)
     */
    private void executeInstantEventDirect(Player player, int amount, String donorName, String streamerId) {
        // 한글 닉네임 적용된 플레이어 이름 가져오기
        String playerDisplayName = getPlayerDisplayName(player);

        // 이벤트 시작 알림
        Bukkit.broadcastMessage(ChatColor.GOLD + "🧀" + playerDisplayName + "님이 " + amount + "치즈을 받았습니다 🧀");

        switch (amount) {
            case 1000:
                executeCheeseGive(player, donorName, streamerId);
                break;
            case 5000:
                executeRandomMobSpawn(player, donorName, streamerId);
                break;
            case 9000:
                executeRandomEffect(player, donorName, streamerId);
                break;
            case 30000:
                executeRandomPlayerTeleport(player, donorName, streamerId);
                break;
            case 100000:
                executePrison(player, donorName, streamerId);
                break;
            case 500000:
                executeInstantDeath(player, donorName, streamerId);
                break;
        }

        getLogger().info("⚡ 즉시 처리 완료: " + donorName + " (" + amount + "원) - " + player.getName());
    }

    /**
     * 대기열에서 다음 이벤트 처리
     */
    private void processNextEvent() {
        synchronized (eventLock) {
            if (isProcessingEvent || globalEventQueue.isEmpty()) {
                return;
            }

            isProcessingEvent = true;
            DonationEvent event = globalEventQueue.poll();

            if (event == null) {
                isProcessingEvent = false;
                return;
            }

            getLogger().info("🎮 치즈 이벤트 처리 시작: " + event.donorName + " (" + event.amount + "원) - 남은 대기열: " + globalEventQueue.size());

            // 메인 스레드에서 이벤트 실행
            new BukkitRunnable() {
                @Override
                public void run() {
                    executeEventSafely(event);
                }
            }.runTask(this);
        }
    }

    /**
     * 안전한 이벤트 실행
     */
    private void executeEventSafely(DonationEvent event) {
        try {
            // 플레이어가 온라인인지 확인
            if (!event.player.isOnline()) {
                getLogger().warning("⚠️ 플레이어 오프라인으로 이벤트 건너뜀: " + event.donorName + " (" + event.amount + "원)");
                finishCurrentEvent();
                return;
            }

            // 실제 이벤트 실행
            executeSpecificEvent(event);

        } catch (Exception e) {
            getLogger().severe("❌ 후원 이벤트 실행 오류: " + e.getMessage());
            e.printStackTrace();
            finishCurrentEvent();
        }
    }

    /**
     * 특정 금액별 이벤트 실행 (시간이 걸리는 이벤트만 남김)
     */
    private void executeSpecificEvent(DonationEvent event) {
        Player player = event.player;
        int amount = event.amount;
        String donorName = event.donorName;
        String streamerId = event.streamerId;

        // 한글 닉네임 적용된 플레이어 이름 가져오기
        String playerDisplayName = getPlayerDisplayName(player);

        // 이벤트 시작 알림 (한글 닉네임 사용)
        Bukkit.broadcastMessage(ChatColor.GOLD + "🧀" + playerDisplayName + "님이 " + amount + "치즈을 받았습니다 🧀");

        if (amount == 10000) {
            // 1만원 - 순회공연 (시간이 걸림)
            executeTourPerformance(player, donorName, streamerId);

        } else if (amount == 7000) {
            // 7천원 - 잭팟 (시간이 걸림)
            executeJackpotInventoryWithQueue(player, donorName, streamerId);

        } else if (amount == 4000) {
            // 4천원 - 사랑해 (시간이 걸림)
            executeLoveMessageWithQueue(player, donorName, streamerId);

        } else {
            // 예상치 못한 이벤트
            getLogger().warning("⚠️ 예상치 못한 이벤트 금액: " + amount);
            finishCurrentEvent();
        }
    }

    /**
     * 현재 이벤트 완료 처리 및 다음 이벤트 실행
     */
    public void finishCurrentEvent() {
        synchronized (eventLock) {
            isProcessingEvent = false;
            getLogger().info("✅ 이벤트 처리 완료 - 남은 대기열: " + globalEventQueue.size());

            // 0.5초 후 다음 이벤트 처리 (이벤트 간 간격)
            new BukkitRunnable() {
                @Override
                public void run() {
                    processNextEvent();
                }
            }.runTaskLater(this, 10L);
        }
    }

    /**
     * 사랑해 메시지 (대기열 지원) - 수정된 버전
     */
    private void executeLoveMessageWithQueue(Player player, String donorName, String streamerId) {
        // 수정된 사랑해 메시지 실행
        executeLoveMessage(player, donorName, streamerId);

        // 점프 및 폭죽 애니메이션 완료 후 이벤트 완료 처리 (8초)
        new BukkitRunnable() {
            @Override
            public void run() {
                finishCurrentEvent();
            }
        }.runTaskLater(this, 160L); // 8초 후 (기존 3초에서 연장)
    }

    /**
     * 잭팟 인벤토리 (대기열 지원)
     */
    private void executeJackpotInventoryWithQueue(Player player, String donorName, String streamerId) {
        // 효과음 추가
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.5f, 2.0f);
        UUID playerId = player.getUniqueId();

        // 이미 슬롯머신이 진행 중인 경우에도 강제로 대기열에서 실행
        if (activeSlotMachines.contains(playerId)) {
            // 기존 슬롯머신을 대기열에 추가하는 대신, 전역 대기열에서 처리하므로 바로 실행
            getLogger().info("🎰 " + player.getName() + " 기존 슬롯머신 진행 중이지만 전역 대기열에서 실행");
        }

        // 슬롯머신 시작
        activeSlotMachines.add(playerId);
        startSlotMachineAnimationWithQueue(player, donorName, streamerId);
    }

    /**
     * 슬롯머신 애니메이션 (대기열 지원)
     */
    private void startSlotMachineAnimationWithQueue(Player player, String donorName, String streamerId) {
        // 슬롯머신 시작 사운드
        player.playSound(player.getLocation(), Sound.BLOCK_DISPENSER_DISPENSE, 0.5f, 1.2f);
        // 기존 슬롯머신 코드와 동일하지만 완료 시 finishCurrentEvent 호출
        String[] fruits = {"🦊", "♠", "⚠", "❓"};

        new BukkitRunnable() {
            final boolean isJackpot = ThreadLocalRandom.current().nextInt(100) < 15; //잭팟 확률
            int frameCount = 0;
            int maxFrames = 80;

            // 슬롯 변수들 (기존과 동일)
            int slot1Index = 0;
            int slot2Index = 0;
            int slot3Index = 0;
            int slot1StopFrame = 50;
            int slot2StopFrame = 65;
            int slot3StopFrame = 75;
            String finalSlot1 = null;
            String finalSlot2 = null;
            String finalSlot3 = null;

            @Override
            public void run() {
                if (!player.isOnline()) {
                    this.cancel();
                    completeSlotMachineWithQueue(player);
                    return;
                }

                // 슬롯머신 애니메이션 로직 (기존과 동일)
                String currentSlot1, currentSlot2, currentSlot3;

                // 슬롯 로직 (기존과 동일하므로 생략...)
                if (frameCount >= slot1StopFrame) {
                    if (finalSlot1 == null) {
                        finalSlot1 = fruits[ThreadLocalRandom.current().nextInt(fruits.length)];
                    }
                    currentSlot1 = finalSlot1;
                } else {
                    slot1Index = (slot1Index + 1) % fruits.length;
                    currentSlot1 = fruits[slot1Index];
                }

                if (frameCount >= slot2StopFrame) {
                    if (finalSlot2 == null) {
                        if (isJackpot) {
                            finalSlot2 = finalSlot1;
                        } else {
                            finalSlot2 = fruits[ThreadLocalRandom.current().nextInt(fruits.length)];
                            if (finalSlot2.equals(finalSlot1)) {
                                do {
                                    finalSlot2 = fruits[ThreadLocalRandom.current().nextInt(fruits.length)];
                                } while (finalSlot2.equals(finalSlot1));
                            }
                        }
                    }
                    currentSlot2 = finalSlot2;
                } else {
                    slot2Index = (slot2Index + 1) % fruits.length;
                    currentSlot2 = fruits[slot2Index];
                }

                if (frameCount >= slot3StopFrame) {
                    if (finalSlot3 == null) {
                        if (isJackpot) {
                            finalSlot3 = finalSlot1;
                        } else {
                            finalSlot3 = fruits[ThreadLocalRandom.current().nextInt(fruits.length)];
                            if (finalSlot3.equals(finalSlot1) && finalSlot2.equals(finalSlot1)) {
                                do {
                                    finalSlot3 = fruits[ThreadLocalRandom.current().nextInt(fruits.length)];
                                } while (finalSlot3.equals(finalSlot1));
                            }
                        }
                    }
                    currentSlot3 = finalSlot3;
                } else {
                    slot3Index = (slot3Index + 1) % fruits.length;
                    currentSlot3 = fruits[slot3Index];
                }

                // Title 표시
                String slotDisplay = "🎰 [ " + currentSlot1 + " | " + currentSlot2 + " | " + currentSlot3 + " ] 🎰";
                String subtitle = frameCount < slot1StopFrame ? "카츠의 슬롯머신 작동중..." :
                        frameCount < slot2StopFrame ? "첫 번째!" :
                                frameCount < slot3StopFrame ? "두 번째!" :
                                        (finalSlot1 != null && finalSlot2 != null && finalSlot3 != null &&
                                                finalSlot1.equals(finalSlot2) && finalSlot2.equals(finalSlot3)) ?
                                                ChatColor.GOLD + "🎉 잭팟! 🎉" : ChatColor.GRAY + "아쉬워요..ㅠ";

                player.sendTitle(ChatColor.YELLOW + slotDisplay, subtitle, 0, 10, 5);
                frameCount++;

                // 슬롯 돌아가는 소리 (가끔씩)
                if (frameCount % 10 == 0 && frameCount < maxFrames - 10) {
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.2f, 1.5f);
                }

                // 슬롯이 멈출 때 효과음
                if (frameCount == slot1StopFrame || frameCount == slot2StopFrame || frameCount == slot3StopFrame) {
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.4f, 1.0f);
                }

                // 슬롯머신 완료
                if (frameCount >= maxFrames && finalSlot1 != null && finalSlot2 != null && finalSlot3 != null) {
                    this.cancel();

                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            boolean actualJackpot = finalSlot1.equals(finalSlot2) && finalSlot2.equals(finalSlot3);
                            showSlotMachineResult(player, donorName, streamerId, actualJackpot);

                            // 3초 후 슬롯머신 완료 및 이벤트 완료
                            new BukkitRunnable() {
                                @Override
                                public void run() {
                                    completeSlotMachineWithQueue(player);
                                }
                            }.runTaskLater(cheeze_donation_plugin.this, 60L);
                        }
                    }.runTaskLater(cheeze_donation_plugin.this, 10L);
                }
            }
        }.runTaskTimer(this, 0L, 1L);
    }

    /**
     * 슬롯머신 완료 (대기열 지원)
     */
    private void completeSlotMachineWithQueue(Player player) {
        UUID playerId = player.getUniqueId();
        activeSlotMachines.remove(playerId);

        getLogger().info("🎰 " + player.getName() + " 슬롯머신 완료 (전역 대기열)");

        // 전역 이벤트 완료
        finishCurrentEvent();
    }

    private void executeCheeseGive(Player player, String donorName, String streamerId) {
        // 효과음
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.5f, 1.0f);
        // 하베스트크래프트 2 치즈 아이템 생성
        try {
            // 방법 1: 직접 Material로 생성 (권장)
            Material cheeseMaterial = Material.valueOf("PAMHC2FOODCORE_CHEESEITEM");
            ItemStack cheese = new ItemStack(cheeseMaterial, 5);

            player.getInventory().addItem(cheese);

            // sendMessage로 메시지 표시
            player.sendMessage(ChatColor.YELLOW + "🧀 1000 치즈를 받아 치즈 5개를 받으셨습니다 🧀");

        } catch (IllegalArgumentException e) {
            // Material을 찾을 수 없는 경우 대체 방법
            getLogger().warning("하베스트크래프트 치즈 아이템을 찾을 수 없습니다. 대체 아이템을 지급합니다.");

            // 대체 아이템으로 황금 당근 지급 (치즈와 비슷한 색상)
            ItemStack cheese = new ItemStack(Material.GOLDEN_CARROT, 5);
            ItemMeta meta = cheese.getItemMeta();
            meta.setDisplayName(ChatColor.YELLOW + "🧀 치즈");
            meta.setLore(Arrays.asList(
                    ChatColor.GRAY + "하베스트크래프트 치즈",
                    ChatColor.AQUA + donorName + "님의 치즈"
            ));
            cheese.setItemMeta(meta);

            player.getInventory().addItem(cheese);

            player.sendMessage(ChatColor.YELLOW + "🧀 1000 치즈를 받아 치즈 5개를 받으셨습니다 🧀");
        }
    }

    /**
     * 사랑해 메시지 (점프 + 낙사 방지로 변경)
     */
    private void executeLoveMessage(Player player, String donorName, String streamerId) {
        // 효과음 추가
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.5f);

        // Title로 하트 메시지 표시
        player.sendTitle(ChatColor.LIGHT_PURPLE + "💖💖💖",
                ChatColor.WHITE + donorName + "님이 하트를 보냈습니다!",
                10, 80, 20);

        // 하트 파티클 효과
        player.getWorld().spawnParticle(org.bukkit.Particle.HEART, player.getLocation().add(0, 2, 0), 15);

        // === 높이 점프 효과 추가 ===
        UUID playerId = player.getUniqueId();

        // 낙사 방지 목록에 추가
        loveFallProtection.add(playerId);

        // 점프 효과 (높이 조절 가능)
        org.bukkit.util.Vector jumpVelocity = new org.bukkit.util.Vector(0, 10, 0); // Y축 3로 높이 점프
        player.setVelocity(jumpVelocity);

        // 점프 사운드 효과
        player.playSound(player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 0.8f, 1.2f);

        // 하트 모양 폭죽
        spawnHeartFireworks(player.getLocation());

        // 8초 후 낙사 방지 해제 (충분한 시간 확보)
        new BukkitRunnable() {
            @Override
            public void run() {
                loveFallProtection.remove(playerId);

                // 착지 알림
                if (player.isOnline()) {
                    player.sendMessage(ChatColor.LIGHT_PURPLE + "💖 사랑의 점프 완료! 💖");
                    player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.8f);
                }
            }
        }.runTaskLater(this, 160L); // 8초 후 (160틱)
    }

    // 하트 모양 폭죽을 연속으로 터뜨리는 메서드
    private void spawnHeartFireworks(Location location) {
        for (int i = 0; i < 2; i++) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    // 플레이어 주변에 랜덤한 위치에서 폭죽 발사
                    Location fireworkLoc = location.clone().add(
                            ThreadLocalRandom.current().nextDouble(-2, 2),
                            ThreadLocalRandom.current().nextDouble(1, 4),
                            ThreadLocalRandom.current().nextDouble(-2, 2)
                    );

                    Firework firework = location.getWorld().spawn(fireworkLoc, Firework.class);
                    FireworkMeta meta = firework.getFireworkMeta();

                    // 하트 모양 효과 생성
                    FireworkEffect effect = FireworkEffect.builder()
                            .with(FireworkEffect.Type.BURST)
                            .withColor(Color.MAROON, Color.RED, Color.FUCHSIA)
                            .withFade(Color.WHITE)
                            .flicker(true)
                            .trail(true)
                            .build();

                    meta.addEffect(effect);
                    meta.setPower(1); // 체공시간 증가
                    firework.setFireworkMeta(meta);

                    // 4000원 후원 폭죽임을 표시하는 메타데이터 추가
                    firework.setMetadata("LOVE_FIREWORK", new FixedMetadataValue(com.example.cheezedonation.cheeze_donation_plugin.this, true));

                    // 데미지 없는 폭죽 목록에 추가
                    noDAMAGEFireworks.add(firework.getUniqueId());

                    // 폭죽이 터진 후 목록에서 제거하기 위한 태스크 (5초 후 자동 제거)
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            noDAMAGEFireworks.remove(firework.getUniqueId());
                        }
                    }.runTaskLater(com.example.cheezedonation.cheeze_donation_plugin.this, 100L); // 5초 후
                }
            }.runTaskLater(this, i * 20L); // 0.5초 간격으로 발사
        }
    }

    /**
     * 낙사 방지 이벤트 핸들러 (새로 추가)
     */
    @EventHandler
    public void onPlayerDamage(org.bukkit.event.entity.EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getEntity();
        UUID playerId = player.getUniqueId();

        // 사랑 점프 낙사 방지
        if (loveFallProtection.contains(playerId)) {
            org.bukkit.event.entity.EntityDamageEvent.DamageCause cause = event.getCause();

            if (cause == org.bukkit.event.entity.EntityDamageEvent.DamageCause.FALL) {
                // 낙사 데미지 완전 취소
                event.setCancelled(true);

                // 착지 효과
                player.getWorld().spawnParticle(org.bukkit.Particle.HEART,
                        player.getLocation().add(0, 0.5, 0), 10);
                player.playSound(player.getLocation(), Sound.BLOCK_WOOL_PLACE, 0.8f, 1.2f);

                // 안전 착지 메시지
                player.sendMessage(ChatColor.LIGHT_PURPLE + "💖 사랑의 힘으로 안전하게 착지했습니다! 💖");

                getLogger().info("💖 " + player.getName() + " 사랑 점프 낙사 방지 발동");
            }
        }
    }

    //    private void executeJackpotInventory(Player player, String donorName, String streamerId) {
//        UUID playerId = player.getUniqueId();
//
//        // 이미 슬롯머신이 진행 중인지 확인
//        if (activeSlotMachines.contains(playerId)) {
//            // 대기열에 추가
//            slotMachineQueue.computeIfAbsent(playerId, k -> new LinkedList<>())
//                    .offer(() -> startSlotMachineAnimation(player, donorName, streamerId));
//
//            player.sendMessage(ChatColor.YELLOW + " 슬롯머신이 이미 진행 중입니다. 대기열에 추가되었습니다.");
//            getLogger().info(player.getName() + " 슬롯머신 대기열에 추가");
//            return;
//        }
//
//        // 슬롯머신 시작
//        activeSlotMachines.add(playerId);
//        startSlotMachineAnimation(player, donorName, streamerId);
//    }
//    private void startSlotMachineAnimation(Player player, String donorName, String streamerId) {
//        // 4종류 과일 슬롯머신 (고정)
//        String[] fruits = {"🦊", "♠", "⚠", "❓"};
//
//        // 슬롯머신 애니메이션 태스크
//        new BukkitRunnable() {
//            // 10% 확률로 잭팟 여부 미리 결정 (BukkitRunnable 내부에서 선언)
//            final boolean isJackpot = ThreadLocalRandom.current().nextInt(100) < 25;
//
//            int frameCount = 0;
//            int maxFrames = 80; // 4초간 애니메이션
//
//            // 각 슬롯의 현재 과일 인덱스
//            int slot1Index = 0;
//            int slot2Index = 0;
//            int slot3Index = 0;
//
//            // 각 슬롯이 멈추는 시점
//            int slot1StopFrame = 50;  // 2.5초에 첫 번째 슬롯 멈춤
//            int slot2StopFrame = 65;  // 3.25초에 두 번째 슬롯 멈춤
//            int slot3StopFrame = 75;  //  3.75초에 세 번째 슬롯 멈춤
//
//            // 최종 결과 과일들 (멈출 때 결정)
//            String finalSlot1 = null;
//            String finalSlot2 = null;
//            String finalSlot3 = null;
//
//            @Override
//            public void run() {
//                if (!player.isOnline()) {
//                    this.cancel();
//                    return;
//                }
//
//                // 디버그 로그 추가 (첫 번째 프레임에서만)
//                if (frameCount == 0) {
//                    getLogger().info("🎰 " + player.getName() + " 슬롯머신 시작 - 잭팟 여부: " + isJackpot);
//                    if (isJackpot) {
//                        getLogger().info("🎰 " + player.getName() + " - 잭팟 당첨! 인벤세이브권 지급 예정");
//                    }
//                }
//
//                // 각 슬롯의 현재 과일 결정
//                String currentSlot1, currentSlot2, currentSlot3;
//
//                if (frameCount >= slot1StopFrame) {
//                    // 첫 번째 슬롯이 멈춘 상태
//                    if (finalSlot1 == null) {
//                        finalSlot1 = fruits[ThreadLocalRandom.current().nextInt(fruits.length)];
//                        cheeze_donation_plugin.this.getLogger().info("🎰 첫 번째 슬롯 결정: " + finalSlot1);
//                    }
//                    currentSlot1 = finalSlot1;
//                } else {
//                    // 첫 번째 슬롯 회전 중
//                    slot1Index = (slot1Index + 1) % fruits.length;
//                    currentSlot1 = fruits[slot1Index];
//                }
//
//                if (frameCount >= slot2StopFrame) {
//                    // 두 번째 슬롯이 멈춘 상태
//                    if (finalSlot2 == null) {
//                        if (isJackpot) {
//                            finalSlot2 = finalSlot1;
//                            cheeze_donation_plugin.this.getLogger().info("🎰 잭팟 모드 - 두 번째 슬롯: " + finalSlot2);
//                        } else {
//                            finalSlot2 = fruits[ThreadLocalRandom.current().nextInt(fruits.length)];
//                            if (finalSlot2.equals(finalSlot1)) {
//                                do {
//                                    finalSlot2 = fruits[ThreadLocalRandom.current().nextInt(fruits.length)];
//                                } while (finalSlot2.equals(finalSlot1));
//                            }
//                            cheeze_donation_plugin.this.getLogger().info("🎰 꽝 모드 - 두 번째 슬롯: " + finalSlot2);
//                        }
//                    }
//                    currentSlot2 = finalSlot2;
//                } else {
//                    // 두 번째 슬롯 회전 중
//                    slot2Index = (slot2Index + 1) % fruits.length;
//                    currentSlot2 = fruits[slot2Index];
//                }
//
//                if (frameCount >= slot3StopFrame) {
//                    // 세 번째 슬롯이 멈춘 상태
//                    if (finalSlot3 == null) {
//                        // 잭팟이면 첫 번째와 같게, 아니면 랜덤
//                        if (isJackpot) {
//                            finalSlot3 = finalSlot1;
//                            getLogger().info("🎰 잭팟 모드 - 세 번째 슬롯: " + finalSlot3);
//                        } else {
//                            finalSlot3 = fruits[ThreadLocalRandom.current().nextInt(fruits.length)];
//                            // 꽝인데 우연히 3개가 같아지면 다르게 변경
//                            if (finalSlot3.equals(finalSlot1) && finalSlot2.equals(finalSlot1)) {
//                                do {
//                                    finalSlot3 = fruits[ThreadLocalRandom.current().nextInt(fruits.length)];
//                                } while (finalSlot3.equals(finalSlot1));
//                            }
//                            getLogger().info("🎰 꽝 모드 - 세 번째 슬롯: " + finalSlot3);
//                        }
//                    }
//                    currentSlot3 = finalSlot3;
//                } else {
//                    // 세 번째 슬롯 회전 중
//                    slot3Index = (slot3Index + 1) % fruits.length;
//                    currentSlot3 = fruits[slot3Index];
//
//                    // 디버그: 세 번째 슬롯이 언제 멈추는지 확인
//                    if (frameCount == slot3StopFrame - 1) {
//                        getLogger().info("🎰 세 번째 슬롯 멈추기 직전 - frameCount: " + frameCount + ", slot3StopFrame: " + slot3StopFrame);
//                    }
//                }
//
//                // 나머지 코드는 동일...
//                String slotDisplay = "[ " + currentSlot1 + " | " + currentSlot2 + " | " + currentSlot3 + " ]";
//
//                String subtitle;
//                if (frameCount < slot1StopFrame) {
//                    subtitle = "카츠의 슬롯머신 작동중...";
//                } else if (frameCount < slot2StopFrame) {
//                    subtitle = "첫 번째!";
//                } else if (frameCount < slot3StopFrame) {
//                    subtitle = "두 번째!";
//                    if (finalSlot1 != null && finalSlot2 != null && finalSlot1.equals(finalSlot2)) {
//                        subtitle = ChatColor.GOLD + finalSlot1 + " 두 개?? 제발..! " + finalSlot1;
//                    }
//                } else {
//                    if (finalSlot1 != null && finalSlot2 != null && finalSlot3 != null &&
//                            finalSlot1.equals(finalSlot2) && finalSlot2.equals(finalSlot3)) {
//                        subtitle = ChatColor.GOLD + "🎉 잭팟! 🎉";
//                    } else {
//                        subtitle = ChatColor.GRAY + "아쉬워요..ㅠ";
//                    }
//                }
//
//                player.sendTitle(ChatColor.YELLOW + slotDisplay, subtitle, 0, 10, 5);
//                frameCount++;
//
//                // 기존 완료 처리 부분을 이렇게 수정
//                if (frameCount >= maxFrames && finalSlot1 != null && finalSlot2 != null && finalSlot3 != null) {
//                    this.cancel();
//
//                    new BukkitRunnable() {
//                        @Override
//                        public void run() {
//                            boolean actualJackpot = finalSlot1 != null && finalSlot2 != null && finalSlot3 != null &&
//                                    finalSlot1.equals(finalSlot2) && finalSlot2.equals(finalSlot3);
//                            getLogger().info("🎰 최종 결과 - 잭팟: " + actualJackpot + " (" + finalSlot1 + ", " + finalSlot2 + ", " + finalSlot3 + ")");
//
//                            showSlotMachineResult(player, donorName, streamerId, actualJackpot);
//
//                            // 슬롯머신 완료 후 처리
//                            new BukkitRunnable() {
//                                @Override
//                                public void run() {
//                                    completeSlotMachine(player);
//                                }
//                            }.runTaskLater(cheeze_donation_plugin.this, 60L); // 3초 후 완료 처리
//                        }
//                    }.runTaskLater(cheeze_donation_plugin.this, 10L);
//                }
//            }
//        }.runTaskTimer(this, 0L, 1L);
//    }
    private void completeSlotMachine(Player player) {
        UUID playerId = player.getUniqueId();

        // 슬롯머신 완료 표시
        activeSlotMachines.remove(playerId);

        // 대기열에 있는 다음 슬롯머신 실행
        Queue<Runnable> queue = slotMachineQueue.get(playerId);
        if (queue != null && !queue.isEmpty()) {
            Runnable nextSlotMachine = queue.poll();

            // 0.5초 후 다음 슬롯머신 실행
            new BukkitRunnable() {
                @Override
                public void run() {
                    activeSlotMachines.add(playerId);
                    nextSlotMachine.run();

                    player.sendMessage(ChatColor.GREEN + "🎰 대기 중이던 슬롯머신을 시작합니다!");
                    getLogger().info("🎰 " + player.getName() + " 대기열 슬롯머신 실행");
                }
            }.runTaskLater(this, 10L);
        }

        getLogger().info("🎰 " + player.getName() + " 슬롯머신 완료. 대기열 개수: " +
                (queue != null ? queue.size() : 0));
    }

    private void showSlotMachineResult(Player player, String donorName, String streamerId, boolean isJackpot) {
        if (isJackpot) {
            // 잭팟 성공!
            showJackpotWin(player, donorName, streamerId);
        } else {
            // 꽝!
            showJackpotLose(player, donorName, streamerId);
        }
    }

    private void showJackpotWin(Player player, String donorName, String streamerId) {
        // 🎉 당첨 사운드 추가 (시작 부분에)
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.5f, 1.2f);

        // 잭팟 성공 시 화려한 애니메이션
        String[] winFrames = {
                "🎰 ✨ !잭팟! ✨ 🎰",
                "🎰 🌟 !잭팟! 🌟 🎰",
                "🎰 💎 !잭팟! 💎 🎰",
                "🎰 🔥 !잭팟! 🔥 🎰",
                "🎰 ⚡ !잭팟! ⚡ 🎰",
                "🎰 🎉 !잭팟! 🎉 🎰"
        };

        new BukkitRunnable() {
            int frameIndex = 0;
            int count = 0;

            @Override
            public void run() {
                if (!player.isOnline() || count >= 30) { // 3초간 표시
                    this.cancel();

                    // 추가 축하 사운드 (애니메이션 끝날 때)
                    player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.5f);

                    // 최종 결과 처리
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            processJackpotWin(player, donorName, streamerId);
                        }
                    }.runTaskLater(com.example.cheezedonation.cheeze_donation_plugin.this, 10L);
                    return;
                }

                // 애니메이션 중간중간 작은 효과음 (0.5초마다)
                if (count % 10 == 0) {
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.4f, 1.8f);
                }

                String currentFrame = winFrames[frameIndex];
                frameIndex = (frameIndex + 1) % winFrames.length;

                player.sendTitle(
                        ChatColor.GOLD + currentFrame,
                        ChatColor.YELLOW + "🎉 잭팟 당첨! 🎉",
                        0, 10, 5
                );

                // 폭죽 효과 (0.5초마다)
                if (count % 8 == 0) {
                    spawnJackpotFireworks(player.getLocation());
                }

                count++;
            }
        }.runTaskTimer(this, 0L, 2L); // 0.1초마다
    }

    private void showJackpotLose(Player player, String donorName, String streamerId) {
        // 💔 실패 사운드 추가 (시작 부분에)
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 0.8f);

        // 꽝 애니메이션 - 깔끔하게 처리
        new BukkitRunnable() {
            int count = 0;

            @Override
            public void run() {
                if (!player.isOnline() || count >= 15) { // 1.5초간 표시
                    this.cancel();

                    // 최종 꽝 사운드
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.5f);

                    // 최종 꽝 메시지
                    player.sendTitle(
                            ChatColor.GRAY + "🎰 아쉬워요 🎰",
                            ChatColor.WHITE + "꽝입니다. " + donorName + "님의 치즈 너무 감사합니다!",
                            10, 60, 20
                    );
                    return;
                }

                // 실패 효과음 (가끔씩)
                if (count % 5 == 0) {
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_SNARE, 0.3f, 0.7f);
                }

                // 아쉬운 표정 애니메이션
                String[] sadEmojis = {"😢", "💔", "😭", "😞"};
                String currentEmoji = sadEmojis[count % sadEmojis.length];

                player.sendTitle(
                        ChatColor.DARK_GRAY + "🎰 " + currentEmoji + " 🎰",
                        ChatColor.GRAY + "아쉽게도...",
                        0, 10, 5
                );

                count++;
            }
        }.runTaskTimer(this, 0L, 4L); // 0.2초마다
    }

    private void processJackpotWin(Player player, String donorName, String streamerId) {
        boolean isTimeBased = ThreadLocalRandom.current().nextBoolean();

        if (isTimeBased) {
            // 5분 시간제 인벤세이브권 (기존과 동일)
            ItemStack inventorySaveItem = new ItemStack(Material.PAPER, 1);
            ItemMeta meta = inventorySaveItem.getItemMeta();
            meta.setDisplayName(ChatColor.GOLD + "✨ 인벤세이브권 (5분) ✨");
            meta.setLore(Arrays.asList(
                    ChatColor.YELLOW + "우클릭하면 5분간 인벤토리가 보호됩니다!",
                    ChatColor.GRAY + "지속시간 동안 사망 시 아이템을 잃지 않습니다.",
                    ChatColor.DARK_GRAY + "스트리머: " + streamerId + "가 뽑음",
                    ChatColor.DARK_PURPLE + "타입: 시간제",
                    ChatColor.RED + "※ 사망 시 자동 소모 XXX!! ※"
            ));
            inventorySaveItem.setItemMeta(meta);

            giveItemStackable(player, inventorySaveItem, "5분 시간제");
        } else {
            // 1회용 인벤세이브권 (스택 가능)
            ItemStack inventorySaveItem = new ItemStack(Material.PAPER, 1);
            ItemMeta meta = inventorySaveItem.getItemMeta();
            meta.setDisplayName(ChatColor.LIGHT_PURPLE + "💎 인벤세이브권 (1회) 💎");
            meta.setLore(Arrays.asList(
                    ChatColor.YELLOW + "사망 시 자동으로 1번 인벤토리가 보호됩니다!",
                    ChatColor.GRAY + "사망하면 자동으로 1개 소모됩니다.",
                    ChatColor.AQUA + "사용시 사용 횟수 만큼 스택이 쌓입니다.",
                    ChatColor.DARK_GRAY + "스트리머: " + streamerId + "가 뽑음",
                    ChatColor.DARK_PURPLE + "타입: 1회용",
                    ChatColor.RED + "※ 2개씩 사라지는 버그가 일어날 수도 있음※"
            ));
            inventorySaveItem.setItemMeta(meta);

            giveItemStackable(player, inventorySaveItem, "1회용");
        }

        // 한글 닉네임 적용된 플레이어 이름 가져오기
        String playerDisplayName = getPlayerDisplayName(player);

        // 잭팟 브로드캐스트 메시지에 한글 닉네임 사용
        Bukkit.broadcastMessage(ChatColor.GOLD + "🎰" + playerDisplayName + "님이 잭팟에 당첨됐습니다! 🎰");
    }

    private void spawnJackpotFireworks(Location location) {
        // 잭팟 당첨 시 화려한 폭죽
        for (int i = 0; i < 1; i++) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    Location fireworkLoc = location.clone().add(
                            ThreadLocalRandom.current().nextDouble(-2, 2),
                            ThreadLocalRandom.current().nextDouble(1, 4),
                            ThreadLocalRandom.current().nextDouble(-2, 2)
                    );

                    Firework firework = location.getWorld().spawn(fireworkLoc, Firework.class);
                    FireworkMeta meta = firework.getFireworkMeta();

                    // 황금빛 폭죽 효과
                    FireworkEffect effect = FireworkEffect.builder()
                            .with(FireworkEffect.Type.BALL_LARGE)
                            .withColor(Color.YELLOW, Color.ORANGE, Color.WHITE)
                            .withFade(Color.YELLOW)
                            .flicker(true)
                            .trail(true)
                            .build();

                    meta.addEffect(effect);
                    meta.setPower(0);
                    firework.setFireworkMeta(meta);

                    // 안전한 폭죽 표시
                    firework.setMetadata("JACKPOT_FIREWORK", new FixedMetadataValue(com.example.cheezedonation.cheeze_donation_plugin.this, true));
                    noDAMAGEFireworks.add(firework.getUniqueId());

                    // 자동 정리
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            noDAMAGEFireworks.remove(firework.getUniqueId());
                        }
                    }.runTaskLater(com.example.cheezedonation.cheeze_donation_plugin.this, 100L);
                }
            }.runTaskLater(this, i * 20L);
        }
    }

    private void giveItemStackable(Player player, ItemStack newItem, String itemType) {
        // 같은 아이템이 인벤토리에 있는지 확인하고 스택
        boolean wasStacked = false;

        for (ItemStack existingItem : player.getInventory().getContents()) {
            if (existingItem != null && isSameItem(existingItem, newItem)) {
                // 스택 가능한 최대 개수 확인
                int maxStack = existingItem.getMaxStackSize();
                int currentAmount = existingItem.getAmount();

                if (currentAmount < maxStack) {
                    existingItem.setAmount(currentAmount + 1);
                    wasStacked = true;

                    player.sendTitle(
                            ChatColor.GOLD + "🎰🌟 잭팟! 🌟🎰",
                            ChatColor.YELLOW + "❇️ " + itemType + " 인벤세이브권 획득! (총 " + existingItem.getAmount() + "개)",
                            10, 80, 20
                    );

                    player.sendMessage(ChatColor.GOLD + "🎰🌟 잭팟으로 " + itemType + " 인벤세이브권을 받았습니다!");
                    getLogger().info("🎰 " + player.getName() + " - " + itemType + " 인벤세이브권 스택 지급 (총 " + existingItem.getAmount() + "개)");
                    break;
                }
            }
        }

        // 스택되지 않았다면 새로 추가
        if (!wasStacked) {
            HashMap<Integer, ItemStack> leftOver = player.getInventory().addItem(newItem);

            if (leftOver.isEmpty()) {
                // 성공적으로 지급됨
                player.sendTitle(
                        ChatColor.GOLD + "🎰🌟 잭팟! 🌟🎰",
                        ChatColor.YELLOW + "❇️ " + itemType + " 인벤세이브권 획득!",
                        10, 80, 20
                );

                player.sendMessage(ChatColor.GOLD + "🎰🌟 잭팟으로 " + itemType + " 인벤세이브권을 받았습니다!");
                getLogger().info("🎰 " + player.getName() + " - " + itemType + " 인벤세이브권 지급");
            } else {
                // 인벤토리가 가득 참
                player.sendTitle(
                        ChatColor.RED + "❌ 인벤토리 가득참! ❌",
                        ChatColor.YELLOW + "인벤토리 공간을 확보해주세요!",
                        10, 80, 20
                );

                player.sendMessage(ChatColor.RED + "❌ 인벤토리가 가득 차서 인벤세이브권을 지급할 수 없습니다!");
                player.sendMessage(ChatColor.YELLOW + "💡 인벤토리 공간을 확보한 후 다시 시도해주세요.");
                getLogger().warning("🎰 " + player.getName() + " - 인벤토리 가득참으로 인벤세이브권 지급 실패");
            }
        }
    }

    /**
     * 두 아이템이 같은 아이템인지 확인 (이름, 로어, 타입 등)
     */
    private boolean isSameItem(ItemStack item1, ItemStack item2) {
        if (item1.getType() != item2.getType()) {
            return false;
        }

        ItemMeta meta1 = item1.getItemMeta();
        ItemMeta meta2 = item2.getItemMeta();

        if (meta1 == null || meta2 == null) {
            return meta1 == meta2;
        }

        // 이름 비교
        if (!Objects.equals(meta1.getDisplayName(), meta2.getDisplayName())) {
            return false;
        }

        // 로어 비교
        return Objects.equals(meta1.getLore(), meta2.getLore());
    }

    private void restartInventorySaveTimer(Player player, int remainingSeconds) {
        UUID playerId = player.getUniqueId();

        // 기존 태스크가 있다면 취소
        if (jackpotTasks.containsKey(playerId)) {
            jackpotTasks.get(playerId).cancel();
            jackpotTasks.remove(playerId);
        }

        // 타이머 표시 태스크 생성
        BukkitRunnable timerTask = new BukkitRunnable() {
            int seconds = remainingSeconds;

            @Override
            public void run() {
                if (!jackpotPlayers.contains(playerId) || !player.isOnline()) {
                    this.cancel();
                    jackpotTasks.remove(playerId);
                    return;
                }

                // 시간 포맷 (분:초)
                int minutes = seconds / 60;
                int secs = seconds % 60;
                String timeFormat = String.format("%d:%02d", minutes, secs);

                // 색상 변경 (마지막 30초는 빨간색)
                ChatColor timeColor = seconds <= 30 ? ChatColor.RED : ChatColor.GREEN;

                // ActionBar로 타이머 표시
                player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                        net.md_5.bungee.api.chat.TextComponent.fromLegacyText(
                                timeColor + "✨ 인벤세이브 시간: " + timeFormat + " ✨"
                        ));

                seconds--;

                // 시간이 다 되면 해제
                if (seconds < 0) {
                    this.cancel();

                    // 인벤세이브 해제
                    if (jackpotPlayers.contains(playerId)) {
                        jackpotPlayers.remove(playerId);
                        inventorySaveStartTime.remove(playerId);
                        jackpotPlayersBackup.remove(playerId);
                        jackpotPlayersLevelBackup.remove(playerId); // 레벨 백업도 제거
                        jackpotTasks.remove(playerId);

                        if (player.isOnline()) {
                            player.sendTitle(ChatColor.YELLOW + "⏰ 인벤세이브 타임 종료!",
                                    ChatColor.WHITE + "인벤세이브가 해제되었습니다🥺",
                                    10, 60, 20);
                            player.sendMessage(ChatColor.YELLOW + "⏰ 인벤세이브가 종료되어 개인 인벤토리 보호가 해제되었습니다!");
                        }
                    }
                }
            }
        };

        // 1초마다 실행
        timerTask.runTaskTimer(this, 0L, 20L);
        jackpotTasks.put(playerId, timerTask);
    }

    private void restartOneTimeInventorySaveAlert(Player player) {
        UUID playerId = player.getUniqueId();

        // 기존 태스크가 있다면 취소
        if (jackpotTasks.containsKey(playerId)) {
            jackpotTasks.get(playerId).cancel();
            jackpotTasks.remove(playerId);
        }

        // ActionBar로 지속적인 알림 재시작
        BukkitRunnable oneTimeAlertTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!oneTimeInventorySavePlayers.contains(playerId) || !player.isOnline()) {
                    this.cancel();
                    return;
                }

                // 현재 활성화된 개수와 인벤토리 개수 표시 (기존 activateOneTimeInventorySave와 동일하게)
                int activatedCount = oneTimeInventorySaveUsageCount.getOrDefault(playerId, 0);
                String message = ChatColor.LIGHT_PURPLE + "💎 활성화: " + activatedCount + "회💎";

                player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                        net.md_5.bungee.api.chat.TextComponent.fromLegacyText(message));
            }
        };

        // 1초마다 실행
        oneTimeAlertTask.runTaskTimer(this, 0L, 20L);
        jackpotTasks.put(playerId, oneTimeAlertTask);
    }

    private void executeRandomEffect(Player player, String donorName, String streamerId) {
        PotionEffectType[] buffEffects = {
                PotionEffectType.SPEED, PotionEffectType.STRENGTH, PotionEffectType.REGENERATION,
                PotionEffectType.RESISTANCE, PotionEffectType.FIRE_RESISTANCE, PotionEffectType.NIGHT_VISION,
                PotionEffectType.HEALTH_BOOST, PotionEffectType.ABSORPTION, PotionEffectType.JUMP_BOOST,
                PotionEffectType.WATER_BREATHING, PotionEffectType.SATURATION, PotionEffectType.INSTANT_HEALTH
        };

        PotionEffectType[] debuffEffects = {
                PotionEffectType.SLOWNESS, PotionEffectType.WEAKNESS, PotionEffectType.POISON,
                PotionEffectType.BLINDNESS, PotionEffectType.NAUSEA, PotionEffectType.HUNGER,
                PotionEffectType.MINING_FATIGUE, PotionEffectType.WITHER, PotionEffectType.LEVITATION
        };

        // 70% 확률로 버프, 30% 확률로 디버프 (몬스터 나올 확률 늘리기와 동일한 원리)
        boolean isBuff = ThreadLocalRandom.current().nextInt(100) < 30;
        PotionEffectType[] effects = isBuff ? buffEffects : debuffEffects;
        PotionEffectType selectedEffect = effects[ThreadLocalRandom.current().nextInt(effects.length)];

        // 효과음 추가 (버프/디버프에 따라 다른 소리)
        if (isBuff) {
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.2f);
        } else {
            player.playSound(player.getLocation(), Sound.ENTITY_WITCH_DRINK, 0.5f, 0.8f);
        }

        // 버프는 3분(3600틱), 디버프는 15초(300틱)으로 시간 조정
        int duration = isBuff ? 3600 : 400; // 버프 3분, 디버프 20초
        int amplifier = ThreadLocalRandom.current().nextInt(2); // 0-1 레벨

        player.addPotionEffect(new PotionEffect(selectedEffect, duration, amplifier));

        String effectType = isBuff ? "버프" : "디버프";
        ChatColor color = isBuff ? ChatColor.GREEN : ChatColor.RED;
        String emoji = isBuff ? "✨" : "💀";

        // 시간 표시 개선
        String timeDisplay = isBuff ? "3분" : "15초";

        // Title로 효과 표시
        player.sendTitle(color + emoji + " " + effectType + "!",
                ChatColor.WHITE + "9000치즈를 받아 " + timeDisplay + "간 " + effectType + " 효과가 발동됩니다!",
                10, 60, 20);
    }

    private void executeRandomMobSpawn(Player player, String donorName, String streamerId) {
        // 효과음 추가
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.5f, 1.0f);

        // 몬스터 종류 대폭 늘리기
        EntityType[] friendlyMobs = {
                EntityType.COW, EntityType.PIG, EntityType.CHICKEN, EntityType.SHEEP, EntityType.WOLF,
                EntityType.CAT, EntityType.HORSE, EntityType.DONKEY, EntityType.LLAMA, EntityType.RABBIT,
                EntityType.OCELOT, EntityType.PARROT, EntityType.TURTLE, EntityType.PANDA, EntityType.BEE,
                EntityType.FOX, EntityType.GOAT, EntityType.AXOLOTL, EntityType.SNOW_GOLEM
        };

        EntityType[] hostileMobs = {
                EntityType.ZOMBIE, EntityType.SKELETON, EntityType.SPIDER, EntityType.CREEPER, EntityType.WITCH,
                EntityType.ENDERMAN, EntityType.BLAZE, EntityType.SLIME, EntityType.MAGMA_CUBE,
                EntityType.SILVERFISH, EntityType.CAVE_SPIDER, EntityType.ZOMBIE_VILLAGER, EntityType.HUSK,
                EntityType.STRAY, EntityType.WITHER_SKELETON, EntityType.VEX, EntityType.VINDICATOR,
                EntityType.EVOKER, EntityType.PHANTOM, EntityType.DROWNED, EntityType.PILLAGER,
                EntityType.RAVAGER, EntityType.PIGLIN, EntityType.ZOMBIFIED_PIGLIN, EntityType.HOGLIN,
                EntityType.ZOGLIN, EntityType.WARDEN
        };

        // 몬스터 나올 확률 80%로 늘리기 (기존 50%에서 증가)
        boolean isFriendly = ThreadLocalRandom.current().nextInt(100) < 20; // 20% 친화적, 80% 적대적
        EntityType[] mobs = isFriendly ? friendlyMobs : hostileMobs;
        EntityType selectedMob = mobs[ThreadLocalRandom.current().nextInt(mobs.length)];

//        // 안전한 소환 위치 찾기 (좁은 공간에서 몹이 껴서 죽는 문제 해결)
//        Location safeLoc = findSafeSpawnLocation(player.getLocation());
//
//        if (safeLoc == null) {
//            player.sendTitle(ChatColor.RED + "❌ 몹 소환 실패!",
//                    ChatColor.WHITE + "안전한 소환 위치를 찾을 수 없습니다.",
//                    10, 60, 20);
//            return;
//        }

        Location spawnLoc = player.getLocation().clone(); // === 수정된 부분: 플레이어 현재 위치에서 바로 소환 ===

        // 플레이어 바로 앞 1블록 위치에 소환 (선택사항)
        spawnLoc.add(player.getLocation().getDirection().multiply(1));

        // 약간 위로 올려서 소환 (몹이 땅에 묻히는 것 방지)
        spawnLoc.add(0, 0.5, 0);

        // 몹 소환 (안전한 소환 위치 찾기 로직 제거)
        org.bukkit.entity.Entity spawnedMob = player.getWorld().spawnEntity(spawnLoc, selectedMob);

        // 한글 닉네임 적용된 플레이어 이름 가져오기
        String playerDisplayName = getPlayerDisplayName(player);

        // 소환된 몹 설정
        if (spawnedMob instanceof org.bukkit.entity.LivingEntity) {
            org.bukkit.entity.LivingEntity livingMob = (org.bukkit.entity.LivingEntity) spawnedMob;

            // 후원자 닉네임으로 몹 이름 설정
            String mobDisplayName = ChatColor.GOLD + "🎁 " + ChatColor.WHITE + donorName + ChatColor.GOLD + "님의 선물";
            livingMob.setCustomName(mobDisplayName);
            livingMob.setCustomNameVisible(true); // 이름 항상 표시

            // 몹에게 잠깐 무적 상태 부여 (질식 방지)
            livingMob.setInvulnerable(true);

            // 2초 후 무적 해제
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (livingMob.isValid() && !livingMob.isDead()) {
                        livingMob.setInvulnerable(false);
                    }
                }
            }.runTaskLater(this, 40L);

            // 몹 소환 알림 메시지에 한글 닉네임 사용
            Bukkit.broadcastMessage(ChatColor.YELLOW + "🎁 " + donorName + "님이 " +
                    playerDisplayName + "님에게 " +
                    (isFriendly ? "친화적인" : "위험한") + " " +
                    selectedMob.name() + "을(를) 소환했습니다!");
        }

        String mobType = isFriendly ? "다행히도" : "도망쳐!";
        ChatColor color = isFriendly ? ChatColor.GREEN : ChatColor.RED;
        String emoji = isFriendly ? "🐷" : "👹";

        // Title로 몹 소환 표시 (후원자 이름 포함)
        player.sendTitle(color + emoji + "5천 치즈를 받아 몹소환! " + emoji,
                ChatColor.WHITE + mobType + " " + ChatColor.GOLD + donorName + "님의 " +
                        ChatColor.WHITE + selectedMob.name() + "이(가) 나타났어요!",
                10, 60, 20);
    }

    // 안전한 소환 위치 찾기 메서드 (새로 추가)
//    private Location findSafeSpawnLocation(Location playerLoc) {
//        int maxAttempts = 20; // 최대 20번 시도
//
//        for (int attempt = 0; attempt < maxAttempts; attempt++) {
//            // 플레이어 주변 8블록 범위에서 랜덤 위치 생성
//            double x = playerLoc.getX() + (ThreadLocalRandom.current().nextDouble() - 0.5) * 16; // ±8블록
//            double z = playerLoc.getZ() + (ThreadLocalRandom.current().nextDouble() - 0.5) * 16; // ±8블록
//
//            // 지면 높이 찾기
//            Location testLoc = new Location(playerLoc.getWorld(), x, playerLoc.getY(), z);
//            Location safeLoc = findGroundLevel(testLoc);
//
//            if (safeLoc != null && isSafeLocation(safeLoc)) {
//                return safeLoc;
//            }
//        }
//
//        // 안전한 위치를 찾지 못한 경우, 플레이어 바로 앞 3블록 위치 사용
//        Location fallbackLoc = playerLoc.clone().add(playerLoc.getDirection().multiply(3));
//        fallbackLoc = findGroundLevel(fallbackLoc);
//
//        if (fallbackLoc != null && isSafeLocation(fallbackLoc)) {
//            return fallbackLoc;
//        }
//
//        return null; // 정말 안전한 위치가 없는 경우
//    }

    // 지면 레벨 찾기 메서드 (새로 추가)
//    private Location findGroundLevel(Location loc) {
//        Location testLoc = loc.clone();
//
//        // 위로 올라가면서 공기 블록 찾기
//        for (int y = 0; y < 10; y++) {
//            testLoc.setY(loc.getY() + y);
//            if (testLoc.getBlock().getType() == Material.AIR &&
//                    testLoc.clone().add(0, 1, 0).getBlock().getType() == Material.AIR) {
//                return testLoc;
//            }
//        }
//
//        // 아래로 내려가면서 지면 찾기
//        for (int y = 0; y < 10; y++) {
//            testLoc.setY(loc.getY() - y);
//            if (testLoc.getBlock().getType() != Material.AIR &&
//                    testLoc.clone().add(0, 1, 0).getBlock().getType() == Material.AIR &&
//                    testLoc.clone().add(0, 2, 0).getBlock().getType() == Material.AIR) {
//                return testLoc.clone().add(0, 1, 0);
//            }
//        }
//
//        return null;
//    }

    // 안전한 위치 판단 메서드 (새로 추가)
//    private boolean isSafeLocation(Location loc) {
//        // 2블록 높이 확인 (몹이 들어갈 수 있는 공간)
//        for (int y = 0; y < 2; y++) {
//            Location checkLoc = loc.clone().add(0, y, 0);
//            Material blockType = checkLoc.getBlock().getType();
//
//            // 고체 블록이면 안전하지 않음
//            if (blockType.isSolid()) {
//                return false;
//            }
//
//            // 용암이나 물 등 위험한 블록 확인
//            if (blockType == Material.LAVA || blockType == Material.WATER ||
//                    blockType == Material.FIRE || blockType == Material.MAGMA_BLOCK) {
//                return false;
//            }
//        }
//
//        // 발밑이 고체 블록인지 확인 (허공에 떠있지 않도록)
//        Location groundLoc = loc.clone().add(0, -1, 0);
//        return groundLoc.getBlock().getType().isSolid();
//    }

    private void executeRandomPlayerTeleport(Player player, String donorName, String streamerId) {
        // 효과음 추가
        player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 1.0f);

        // 치지직 연동된 온라인 플레이어들만 목록에 포함
        List<Player> onlinePlayers = new ArrayList<>();
        Set<String> connectedPlayers = cheezeManager.getConnectedPlayers(); // 치지직 연동된 플레이어 목록

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.equals(player) && connectedPlayers.contains(p.getName())) {
                onlinePlayers.add(p);
            }
        }

        if (onlinePlayers.isEmpty()) {
            player.sendTitle(ChatColor.RED + "❌ 텔레포트 실패!",
                    ChatColor.WHITE + "3만 치즈를 받았지만 치지직 연동된 플레이어가 없어 텔레포트 실패.",
                    10, 60, 20);
            return;
        }

        Player targetPlayer = onlinePlayers.get(ThreadLocalRandom.current().nextInt(onlinePlayers.size()));
        player.teleport(targetPlayer.getLocation());

        // Title로 텔레포트 알림
        player.sendTitle(ChatColor.BLUE + "⭐ 텔레포트!",
                ChatColor.WHITE + " 3만 치즈를 받아 " + targetPlayer.getName() + "님에게 이동!",
                10, 60, 20);

        targetPlayer.sendMessage(ChatColor.BLUE + "⭐ 3만 치즈를 받아 " + player.getName() + "님이 당신에게 등☆장! ⭐");
    }

    /**
     * 감옥 실행 (10만원) - 재접속 시 상태 유지 기능 추가
     */
    private void executePrison(Player player, String donorName, String streamerId) {
        // 효과음 추가
        player.playSound(player.getLocation(), Sound.BLOCK_IRON_DOOR_CLOSE, 0.5f, 0.5f);
        UUID playerId = player.getUniqueId();

        // 기존 감옥 태스크가 있다면 취소
        if (prisonTasks.containsKey(playerId)) {
            prisonTasks.get(playerId).cancel();
            prisonTasks.remove(playerId);
        }

        // 원래 위치 및 게임모드 저장
        originalLocations.put(playerId, player.getLocation());
        originalGameModes.put(playerId, player.getGameMode());

        // 감옥 시작 시간 기록 (중요!)
        prisonStartTime.put(playerId, System.currentTimeMillis());

        // Adventure 모드로 변경
        player.setGameMode(org.bukkit.GameMode.ADVENTURE);

        // config에서 감옥 좌표 가져오기
        FileConfiguration config = getConfig();
        String worldName = config.getString("custom-prison.world", "world");
        double x = config.getDouble("custom-prison.x", 100);
        double y = config.getDouble("custom-prison.y", 70);
        double z = config.getDouble("custom-prison.z", 100);

        Location prisonLoc = new Location(Bukkit.getWorld(worldName), x, y, z);

        // 감옥으로 텔레포트
        player.teleport(prisonLoc);
        prisonedPlayers.put(playerId, prisonLoc);

        // 초기 Title로 감옥 알림
        player.sendTitle(ChatColor.DARK_GRAY + "⛓ 철컹철컹 ⛓",
                ChatColor.WHITE + "10만 치즈로 5분간 유배 당했습니다!",
                10, 80, 20);

        // 한글 닉네임 적용된 플레이어 이름 가져오기
        String playerDisplayName = getPlayerDisplayName(player);

        // 감옥 브로드캐스트 메시지에 한글 닉네임 사용
        Bukkit.broadcastMessage(ChatColor.DARK_GRAY + "⛓ " + playerDisplayName + "님이 10만 치즈로 인해 유배됐습니다! ⛓");

        // 5분 타이머 시작
        startPrisonTimer(player, 300); // 300초 = 5분
    }

    /**
     * 감옥 타이머 시작 (재접속 시에도 호출 가능)
     */
    private void startPrisonTimer(Player player, int remainingSeconds) {
        UUID playerId = player.getUniqueId();

        // 기존 타이머가 있다면 취소
        if (prisonTasks.containsKey(playerId)) {
            prisonTasks.get(playerId).cancel();
            prisonTasks.remove(playerId);
        }

        // 타이머 표시 태스크 생성
        BukkitRunnable timerTask = new BukkitRunnable() {
            int seconds = remainingSeconds;

            @Override
            public void run() {
                if (!prisonedPlayers.containsKey(playerId) || !player.isOnline()) {
                    this.cancel();
                    prisonTasks.remove(playerId);
                    return;
                }

                // 시간 포맷 (분:초)
                int minutes = seconds / 60;
                int secs = seconds % 60;
                String timeFormat = String.format("%d:%02d", minutes, secs);

                // 색상 변경 (마지막 30초는 빨간색)
                ChatColor timeColor = seconds <= 30 ? ChatColor.RED : ChatColor.YELLOW;

                // ActionBar로 타이머 표시
                player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                        net.md_5.bungee.api.chat.TextComponent.fromLegacyText(
                                timeColor + "⛓ 감옥 남은시간: " + timeFormat + " ⛓"
                        ));

                seconds--;

                // 시간이 다 되면 해제
                if (seconds < 0) {
                    this.cancel();
                    releasePrisoner(player);
                }
            }
        };

        // 1초마다 실행
        timerTask.runTaskTimer(this, 20L, 20L);
        prisonTasks.put(playerId, timerTask);
    }

    /**
     * 감옥에서 해제
     */
    private void releasePrisoner(Player player) {
        UUID playerId = player.getUniqueId();

        if (prisonedPlayers.containsKey(playerId)) {
            Location originalLoc = originalLocations.get(playerId);
            GameMode originalGameMode = originalGameModes.get(playerId);

            // 한글 닉네임 적용된 플레이어 이름 가져오기
            String playerDisplayName = getPlayerDisplayName(player);

            if (originalLoc != null && player.isOnline()) {
                player.teleport(originalLoc);

                // 게임모드를 원래대로 복원
                if (originalGameMode != null) {
                    player.setGameMode(originalGameMode);
                } else {
                    player.setGameMode(org.bukkit.GameMode.SURVIVAL); // 기본값
                }

                // Title로 해제 알림
                player.sendTitle(ChatColor.GREEN + "🔓 자유! 🔓",
                        ChatColor.WHITE + "유배가 풀렸습니다!",
                        10, 60, 20);

                player.sendMessage(ChatColor.GREEN + "🔓 해방되었습니다! 🔓");
                Bukkit.broadcastMessage(ChatColor.GREEN + "🔓 " + playerDisplayName + "님이 해방되었습니다! 🔓");
            }

            // 감옥 관련 데이터 정리
            prisonedPlayers.remove(playerId);
            originalLocations.remove(playerId);
            originalGameModes.remove(playerId);
            prisonStartTime.remove(playerId); // 시작 시간도 제거

            // 태스크 정리
            if (prisonTasks.containsKey(playerId)) {
                prisonTasks.remove(playerId);
            }

            getLogger().info("🔓 " + player.getName() + " 감옥에서 해제됨");
        }
    }

    /**
     * 순회공연 실행 (30만원)
     */
    private void executeTourPerformance(Player player, String donorName, String streamerId) {
        // 효과음 추가
        player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 1.0f);
        UUID playerId = player.getUniqueId();

        // 이미 순회공연 중인지 확인
        if (activeTours.contains(playerId)) {
            player.sendMessage(ChatColor.RED + "❌ 이미 순회공연이 진행 중입니다! ❌");
            finishCurrentEvent();
            return;
        }

        // 치지직 연동된 온라인 플레이어들만 목록에 포함 (기존 제외 목록 + 치지직 연동 조건)
        List<Player> audiencePlayers = new ArrayList<>();
        Set<String> connectedPlayers = cheezeManager.getConnectedPlayers(); // 치지직 연동된 플레이어 목록

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.equals(player) && p.isOnline() &&
                    !tourExcludedPlayers.contains(p.getUniqueId()) &&  // 기존 제외 목록 유지
                    connectedPlayers.contains(p.getName())) {           // 치지직 연동 조건 추가
                audiencePlayers.add(p);
            }
        }

        if (audiencePlayers.isEmpty()) {
            player.sendTitle(ChatColor.RED + "❌ 순회공연 실패! ❌",
                    ChatColor.WHITE + "치지직 연동된 방문 대상이 없습니다!",
                    10, 60, 20);
            finishCurrentEvent();
            return;
        }

        // 순회공연 시작
        activeTours.add(playerId);
        tourOriginalLocations.put(playerId, player.getLocation().clone());

        // 연동 상태 정보를 포함한 방송 알림
        int totalOnline = Bukkit.getOnlinePlayers().size() - 1; // 자신 제외
        int excludedCount = tourExcludedPlayers.size();
        int connectedCount = audiencePlayers.size();

        player.sendMessage(ChatColor.GOLD + "🎭 30만 치즈를 받아 잠시후 순회공연이 시작됩니다! 🎭");
        player.sendMessage(ChatColor.YELLOW + "🔗 치지직 연동 대상: " + connectedCount + "명 / 전체 온라인: " + totalOnline + "명");

        if (excludedCount > 0) {
            player.sendMessage(ChatColor.GRAY + "🚫 관리자 제외: " + excludedCount + "명");
        }

        // 5초 카운트다운 시작
        startTourCountdown(player, donorName, streamerId, audiencePlayers);
    }

    /**
     * 순회공연 카운트다운
     */
    private void startTourCountdown(Player performer, String donorName, String streamerId, List<Player> audience) {
        BukkitRunnable countdownTask = new BukkitRunnable() {
            int countdown = 5;

            @Override
            public void run() {
                if (!performer.isOnline()) {
                    this.cancel();
                    completeTourPerformance(performer);
                    return;
                }

                if (countdown > 0) {
                    // 카운트다운 표시
                    String countdownMessage = ChatColor.GOLD + "🎭 순회공연 출발까지: " + ChatColor.RED + countdown + ChatColor.GOLD + "초!";
                    performer.sendTitle(ChatColor.GOLD + "🎭 순회공연 준비~ 🎭",
                            ChatColor.RED + String.valueOf(countdown),
                            0, 25, 5);

                    performer.sendMessage(countdownMessage);

                    // 카운트다운 사운드 효과
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 1.0f + (countdown * 0.2f));
                    }

                    countdown--;
                } else {
                    // 카운트다운 완료, 순회공연 시작
                    this.cancel();

                    performer.sendTitle(ChatColor.GOLD + "🎭 순회공연 출발! 🎭",
                            ChatColor.YELLOW + "지금 만나러 갑니다!",
                            10, 40, 10);

                    // 실제 순회공연 시작
                    startTourVisits(performer, donorName, streamerId, audience, 0);
                }
            }
        };

        UUID performerId = performer.getUniqueId();
        tourTasks.put(performerId, countdownTask);
        countdownTask.runTaskTimer(this, 0L, 20L); // 1초마다
    }

    /**
     * 순회공연 방문 시작
     */
    private void startTourVisits(Player performer, String donorName, String streamerId, List<Player> audience, int currentIndex) {
        UUID performerId = performer.getUniqueId();

        if (!performer.isOnline() || !activeTours.contains(performerId)) {
            completeTourPerformance(performer);
            return;
        }

        // 모든 관객을 다 방문했으면 원래 자리로 복귀
        if (currentIndex >= audience.size()) {
            returnToOriginalLocation(performer, donorName, streamerId);
            return;
        }

        Player currentAudience = audience.get(currentIndex);

        // 관객이 오프라인이면 다음 관객으로
        if (!currentAudience.isOnline()) {
            startTourVisits(performer, donorName, streamerId, audience, currentIndex + 1);
            return;
        }

        // 현재 관객에게 텔레포트
        Location audienceLocation = currentAudience.getLocation().clone();
        performer.teleport(audienceLocation);

        // 방문 알림
        performer.sendMessage(ChatColor.AQUA + "🎭 " + currentAudience.getName() + "님을 방문 중... (" + (currentIndex + 1) + "/" + audience.size() + ")");

        // 파티클 효과
        performer.getWorld().spawnParticle(Particle.FIREWORK, performer.getLocation().add(0, 2, 0), 20);

        // 5초 후 다음 관객으로 이동
        BukkitRunnable visitTask = new BukkitRunnable() {
            @Override
            public void run() {
                startTourVisits(performer, donorName, streamerId, audience, currentIndex + 1);
            }
        };

        tourTasks.put(performerId, visitTask);
        visitTask.runTaskLater(this, 100L); // 5초 후 (100틱)
    }

    /**
     * 원래 위치로 복귀
     */
    private void returnToOriginalLocation(Player performer, String donorName, String streamerId) {
        UUID performerId = performer.getUniqueId();
        Location originalLocation = tourOriginalLocations.get(performerId);

        if (originalLocation != null && performer.isOnline()) {
            performer.teleport(originalLocation);

            performer.sendTitle(ChatColor.GREEN + "🎭 순회공연 끝! 🎭",
                    ChatColor.WHITE + "원래 자리로 되돌아왔습니다!",
                    10, 80, 20);

            performer.sendMessage(ChatColor.GREEN + "🎪 순회공연이 성공적으로 끝났습니다! 🎪");

            // 축하 폭죽
            spawnCelebrationFireworks(originalLocation);
        }

        // 3초 후 완전히 종료
        new BukkitRunnable() {
            @Override
            public void run() {
                completeTourPerformance(performer);
            }
        }.runTaskLater(this, 60L);
    }

    /**
     * 순회공연 완료 처리
     */
    private void completeTourPerformance(Player performer) {
        UUID performerId = performer.getUniqueId();

        // 정리 작업
        activeTours.remove(performerId);
        tourOriginalLocations.remove(performerId);

        // 실행 중인 태스크 정리
        if (tourTasks.containsKey(performerId)) {
            BukkitRunnable task = tourTasks.get(performerId);
            if (task != null) {
                task.cancel();
            }
            tourTasks.remove(performerId);
        }

        getLogger().info("🎭 " + performer.getName() + " 순회공연 완료");

        // 전역 이벤트 완료
        finishCurrentEvent();
    }

    /**
     * 순회공연 제외 명령어 처리
     */
    private boolean handleTourExclude(CommandSender sender, String[] args) {
        if (!sender.hasPermission("cheeze.donation.admin")) {
            sender.sendMessage(ChatColor.RED + "❌ 관리자만 사용할 수 있습니다!");
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "사용법: /치즈설정 순회제외 <플레이어>");
            return true;
        }

        String targetName = args[1];
        Player targetPlayer = Bukkit.getPlayer(targetName);

        if (targetPlayer == null) {
            sender.sendMessage(ChatColor.RED + "❌ 플레이어를 찾을 수 없습니다: " + targetName);
            return true;
        }

        UUID targetId = targetPlayer.getUniqueId();

        if (tourExcludedPlayers.contains(targetId)) {
            sender.sendMessage(ChatColor.YELLOW + "⚠️ " + targetName + "은(는) 이미 순회공연에서 제외되어 있습니다!");
            return true;
        }

        tourExcludedPlayers.add(targetId);
        sender.sendMessage(ChatColor.GREEN + "✅ " + targetName + "을(를) 순회공연에서 제외했습니다!");

        // 대상 플레이어에게도 알림
        targetPlayer.sendMessage(ChatColor.YELLOW + "⚠️ 관리자가 당신을 순회공연에서 제외했습니다.");
        targetPlayer.sendMessage(ChatColor.GRAY + "설정자: " + sender.getName());

        getLogger().info("관리자 " + sender.getName() + "이(가) " + targetName + "을(를) 순회공연에서 제외");
        return true;
    }

    /**
     * 순회공연 제외 해제 명령어 처리
     */
    private boolean handleTourUnexclude(CommandSender sender, String[] args) {
        if (!sender.hasPermission("cheeze.donation.admin")) {
            sender.sendMessage(ChatColor.RED + "❌ 관리자만 사용할 수 있습니다!");
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "사용법: /치즈설정 순회포함 <플레이어>");
            return true;
        }

        String targetName = args[1];
        Player targetPlayer = Bukkit.getPlayer(targetName);

        if (targetPlayer == null) {
            sender.sendMessage(ChatColor.RED + "❌ 플레이어를 찾을 수 없습니다: " + targetName);
            return true;
        }

        UUID targetId = targetPlayer.getUniqueId();

        if (!tourExcludedPlayers.contains(targetId)) {
            sender.sendMessage(ChatColor.YELLOW + "⚠️ " + targetName + "은(는) 순회공연 제외 목록에 없습니다!");
            return true;
        }

        tourExcludedPlayers.remove(targetId);
        sender.sendMessage(ChatColor.GREEN + "✅ " + targetName + "을(를) 순회공연에 다시 포함했습니다!");

        // 대상 플레이어에게도 알림
        targetPlayer.sendMessage(ChatColor.GREEN + "✅ 관리자가 당신을 순회공연에 다시 포함했습니다!");
        targetPlayer.sendMessage(ChatColor.GRAY + "설정자: " + sender.getName());

        getLogger().info("관리자 " + sender.getName() + "이(가) " + targetName + "의 순회공연 제외를 해제");
        return true;
    }

    /**
     * 순회공연 제외 목록 확인 명령어 처리
     */
    private boolean handleTourExcludeList(CommandSender sender) {
        if (!sender.hasPermission("cheeze.donation.admin")) {
            sender.sendMessage(ChatColor.RED + "❌ 관리자만 사용할 수 있습니다!");
            return true;
        }

        sender.sendMessage(ChatColor.GOLD + "=== 순회공연 제외 목록 ===");

        if (tourExcludedPlayers.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "제외된 플레이어가 없습니다.");
            return true;
        }

        int count = 1;
        for (UUID playerId : tourExcludedPlayers) {
            Player player = Bukkit.getPlayer(playerId);
            String playerName = player != null ? player.getName() :
                    Bukkit.getOfflinePlayer(playerId).getName();
            String status = player != null && player.isOnline() ?
                    ChatColor.GREEN + " (온라인)" : ChatColor.GRAY + " (오프라인)";

            sender.sendMessage(ChatColor.WHITE + String.valueOf(count) + ". " +
                    ChatColor.YELLOW + playerName + status);
            count++;
        }

        sender.sendMessage(ChatColor.AQUA + "총 " + tourExcludedPlayers.size() + "명이 제외되어 있습니다.");
        return true;
    }

    /**
     * 축하 폭죽 발사
     */
    private void spawnCelebrationFireworks(Location location) {
        for (int i = 0; i < 2; i++) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    Location fireworkLoc = location.clone().add(
                            ThreadLocalRandom.current().nextDouble(-2, 2),
                            ThreadLocalRandom.current().nextDouble(1, 4),
                            ThreadLocalRandom.current().nextDouble(-2, 2)
                    );

                    Firework firework = location.getWorld().spawn(fireworkLoc, Firework.class);
                    FireworkMeta meta = firework.getFireworkMeta();

                    FireworkEffect effect = FireworkEffect.builder()
                            .with(FireworkEffect.Type.BALL_LARGE)
                            .withColor(Color.YELLOW, Color.ORANGE, Color.YELLOW)
                            .withFade(Color.WHITE)
                            .flicker(true)
                            .trail(true)
                            .build();

                    meta.addEffect(effect);
                    meta.setPower(1);
                    firework.setFireworkMeta(meta);

                    // 안전한 폭죽 표시
                    firework.setMetadata("TOUR_FIREWORK", new FixedMetadataValue(cheeze_donation_plugin.this, true));
                    noDAMAGEFireworks.add(firework.getUniqueId());

                    // 자동 정리
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            noDAMAGEFireworks.remove(firework.getUniqueId());
                        }
                    }.runTaskLater(cheeze_donation_plugin.this, 100L);
                }
            }.runTaskLater(this, i * 20L); // 0.5초 간격
        }
    }

    private void executeInstantDeath(Player player, String donorName, String streamerId) {
        // 효과음 추가
        player.playSound(player.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.5f, 0.8f);
        // Title로 즉사 알림
        player.sendTitle(ChatColor.DARK_RED + "💀 끄앙주금 💀",
                ChatColor.WHITE + donorName + "50만 치즈를 받아서 행복한 죽음을 맞이했습니다..",
                10, 60, 20);

        // 한글 닉네임 적용된 플레이어 이름 가져오기
        String playerDisplayName = getPlayerDisplayName(player);

        // 1초 후 즉사 (드라마틱한 효과)
        new BukkitRunnable() {
            @Override
            public void run() {
                player.setHealth(0);
                // 즉사 브로드캐스트 메시지에 한글 닉네임 사용
                Bukkit.broadcastMessage(ChatColor.DARK_RED + "💀 [" + playerDisplayName + "] 님이 50만 치즈를 받아 즉사했습니다! 💀");
            }
        }.runTaskLater(this, 20L);
    }

    // 폭죽 데미지 방지 이벤트 핸들러
    @EventHandler
    public void onFireworkDamage(EntityDamageByEntityEvent event) {
        // 데미지를 입히는 엔티티가 폭죽인지 확인
        if (event.getDamager() instanceof Firework) {
            Firework firework = (Firework) event.getDamager();

            // 4000원 후원 폭죽 (사랑해 폭죽)인지 확인
            if (firework.hasMetadata("LOVE_FIREWORK") || noDAMAGEFireworks.contains(firework.getUniqueId())) {
                event.setCancelled(true); // 데미지 취소
            }
        }
    }

    /**
     * 플레이어 접속 시 감옥 상태 복구 (기존 onPlayerJoin 이벤트에 추가)
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        // 시간제 인벤세이브가 활성화된 플레이어인지 확인
        if (jackpotPlayers.contains(playerId) && inventorySaveStartTime.containsKey(playerId)) {
            // 경과 시간 계산
            long startTime = inventorySaveStartTime.get(playerId);
            long elapsedTime = (System.currentTimeMillis() - startTime) / 1000; // 초 단위
            int remainingSeconds = 300 - (int) elapsedTime; // 5분 = 300초

            if (remainingSeconds > 0) {
                // 남은 시간이 있으면 타이머 재시작
                restartInventorySaveTimer(player, remainingSeconds);
            } else {
                // 시간이 다 지났으면 인벤세이브 해제
                jackpotPlayers.remove(playerId);
                inventorySaveStartTime.remove(playerId);
                jackpotPlayersBackup.remove(playerId);

                player.sendMessage(ChatColor.YELLOW + "⏰ 인벤세이브 종료! 인벤세이브가 해제되었습니다. 🥺");
            }
        }

        // 1회용 인벤세이브가 활성화된 플레이어인지 확인
        if (oneTimeInventorySavePlayers.contains(playerId)) {
            // 1회용 인벤세이브 알림 재시작
            restartOneTimeInventorySaveAlert(player);
        }

        // 감옥 상태 복구 (새로 추가된 부분)
        if (prisonedPlayers.containsKey(playerId) && prisonStartTime.containsKey(playerId)) {
            // 경과 시간 계산
            long startTime = prisonStartTime.get(playerId);
            long elapsedTime = (System.currentTimeMillis() - startTime) / 1000; // 초 단위
            int remainingSeconds = 300 - (int) elapsedTime; // 5분 = 300초

            if (remainingSeconds > 0) {
                // 남은 시간이 있으면 감옥으로 다시 텔레포트 및 타이머 재시작
                Location prisonLoc = prisonedPlayers.get(playerId);
                if (prisonLoc != null) {
                    player.teleport(prisonLoc);

                    // 게임모드 복원
                    GameMode originalGameMode = originalGameModes.get(playerId);
                    if (originalGameMode != null) {
                        // 감옥 상태이므로 Adventure 모드 유지
                        player.setGameMode(org.bukkit.GameMode.ADVENTURE);
                    }

                    // 남은 시간으로 타이머 재시작
                    startPrisonTimer(player, remainingSeconds);

                    player.sendMessage(ChatColor.DARK_GRAY + "⛓ 감옥에 갇혀있는 상태입니다! ⛓");
                    player.sendTitle(ChatColor.DARK_GRAY + "⛓ 감옥 복귀 ⛓",
                            ChatColor.WHITE + "남은 시간: " + (remainingSeconds / 60) + "분 " + (remainingSeconds % 60) + "초",
                            10, 60, 20);

                    getLogger().info("⛓ " + player.getName() + " 감옥 상태 복구 (남은 시간: " + remainingSeconds + "초)");
                }
            } else {
                // 시간이 다 지났으면 감옥에서 해제
                releasePrisoner(player);
                player.sendMessage(ChatColor.GREEN + "🔓 감옥 시간이 종료되어 해방되었습니다! 🔓");
            }
        }
    }

    // 플레이어 사망 시 잭팟 효과가 있으면 인벤토리 보호
    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        UUID playerId = player.getUniqueId();

        // 시간제 인벤세이브가 활성화되어 있는 경우 (최우선)
        if (jackpotPlayers.contains(playerId)) {
            // 시간제 인벤세이브 처리
            processTimeBasedInventorySave(player, event);
            return;
        }

        // 1회용 인벤세이브 우선순위 처리
        if (oneTimeInventorySavePlayers.contains(playerId)) {
            // 우선순위 1: 활성화된 1회용 인벤세이브권 사용
            processActivatedOneTimeInventorySave(player, event);
            return;
        }

        // 우선순위 2: 인벤토리에 있는 1회용 인벤세이브권 자동 사용
        if (hasOneTimeInventorySaveItem(player)) {
            processInventoryOneTimeInventorySave(player, event);
            return;
        }

        // 🆕 인벤세이브권이 아예 없는 경우 - 강제로 아이템 드롭 및 클리어
        // 기존 keepInventory 설정을 무시하고 강제로 아이템을 잃게 만들기
        forceDropAllItems(player, event);
    }

    // 🆕 새로운 메서드: 강제 아이템 드롭
    private void forceDropAllItems(Player player, PlayerDeathEvent event) {
        Location deathLocation = player.getLocation();

        // 1. 인벤토리의 모든 아이템을 직접 월드에 드롭
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() != Material.AIR) {
                deathLocation.getWorld().dropItemNaturally(deathLocation, item.clone());
            }
        }

        // 2. 방어구도 직접 월드에 드롭
        for (ItemStack armor : player.getInventory().getArmorContents()) {
            if (armor != null && armor.getType() != Material.AIR) {
                deathLocation.getWorld().dropItemNaturally(deathLocation, armor.clone());
            }
        }

        // 3. 오프핸드 아이템도 직접 월드에 드롭
        ItemStack offHand = player.getInventory().getItemInOffHand();
        if (offHand != null && offHand.getType() != Material.AIR) {
            deathLocation.getWorld().dropItemNaturally(deathLocation, offHand.clone());
        }

        // 4. 경험치 오브 직접 스폰
        int totalExp = player.getTotalExperience();
        if (totalExp > 0) {
            // 경험치를 적절한 크기로 나누어서 여러 개의 경험치 오브 생성
            while (totalExp > 0) {
                int expToDrop = Math.min(totalExp, 1000); // 한 번에 최대 1000 경험치
                deathLocation.getWorld().spawn(deathLocation, org.bukkit.entity.ExperienceOrb.class)
                        .setExperience(expToDrop);
                totalExp -= expToDrop;
            }
        }

        // 5. 인벤토리 즉시 클리어
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setItemInOffHand(null);

        // 6. 레벨과 경험치도 즉시 초기화
        player.setLevel(0);
        player.setExp(0);
        player.setTotalExperience(0);

        // 7. event.getDrops()도 클리어 (keepInventory가 이것을 무시하지 못하도록)
        event.getDrops().clear();
        event.setDroppedExp(0);

        getLogger().info("💀 " + player.getName() + " - 인벤세이브권 없음으로 아이템 강제 드롭");
    }

    /**
     * 시간제 인벤세이브 처리 (레벨 백업 추가)
     */
    private void processTimeBasedInventorySave(Player player, PlayerDeathEvent event) {
        UUID playerId = player.getUniqueId();

        // 죽기 직전에 인벤토리 백업
        ItemStack[] deathInventory = new ItemStack[player.getInventory().getSize()];
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            ItemStack item = player.getInventory().getItem(i);
            deathInventory[i] = item != null ? item.clone() : null;
        }

        // === 레벨 및 경험치 백업 추가 ===
        // 기존 인벤토리 백업에 레벨 정보도 함께 저장
        jackpotPlayersBackup.put(playerId, deathInventory);

        // 레벨 정보 별도 저장을 위한 새로운 Map 추가 (클래스 상단에 선언 필요)
        Map<String, Object> levelData = new HashMap<>();
        levelData.put("level", player.getLevel());
        levelData.put("exp", player.getExp());
        levelData.put("totalExp", player.getTotalExperience());

        // 레벨 백업 저장 (새로운 Map에 저장)
        jackpotPlayersLevelBackup.put(playerId, levelData);

        // 드롭 아이템 완전 제거
        event.getDrops().clear();
        event.setDroppedExp(0); // 경험치 드롭도 방지
        player.getInventory().clear();

        getLogger().info("💎 " + player.getName() + " - 시간제 인벤세이브 발동 (레벨 " + player.getLevel() + " 포함)");
        player.sendMessage(ChatColor.GREEN + "🎰🍀 시간제 인벤세이브 발동! (레벨 포함) 🍀🎰");
    }

    /**
     * 활성화된 1회용 인벤세이브 처리 (레벨 백업 추가)
     */
    private void processActivatedOneTimeInventorySave(Player player, PlayerDeathEvent event) {
        UUID playerId = player.getUniqueId();

        // 죽기 직전에 인벤토리 백업 (1회용 인벤세이브권 제거하지 않음)
        ItemStack[] deathInventory = new ItemStack[player.getInventory().getSize()];
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            ItemStack item = player.getInventory().getItem(i);
            deathInventory[i] = item != null ? item.clone() : null;
        }

        // === 레벨 및 경험치 백업 추가 ===
        jackpotPlayersBackup.put(playerId, deathInventory);

        // 레벨 정보 저장
        Map<String, Object> levelData = new HashMap<>();
        levelData.put("level", player.getLevel());
        levelData.put("exp", player.getExp());
        levelData.put("totalExp", player.getTotalExperience());
        jackpotPlayersLevelBackup.put(playerId, levelData);

        // 드롭 아이템 완전 제거
        event.getDrops().clear();
        event.setDroppedExp(0); // 경험치 드롭도 방지
        player.getInventory().clear();

        // 활성화된 1회용 인벤세이브 사용 처리
        int currentUsageCount = oneTimeInventorySaveUsageCount.getOrDefault(playerId, 1);
        if (currentUsageCount > 1) {
            oneTimeInventorySaveUsageCount.put(playerId, currentUsageCount - 1);
            getLogger().info("💎 " + player.getName() + " - 활성화된 1회용 인벤세이브 사용 (레벨 " + player.getLevel() + " 포함, " + (currentUsageCount - 1) + "회 남음)");
            player.sendMessage(ChatColor.LIGHT_PURPLE + "💎 활성화된 1회용 인벤세이브권이 사용되었습니다! (레벨 포함)");
            player.sendMessage(ChatColor.YELLOW + "💎 " + (currentUsageCount - 1) + "회 활성화 중 💎");
        } else {
            oneTimeInventorySaveUsageCount.remove(playerId);
            oneTimeInventorySavePlayers.remove(playerId);
            if (jackpotTasks.containsKey(playerId)) {
                jackpotTasks.get(playerId).cancel();
                jackpotTasks.remove(playerId);
            }
            getLogger().info("💎 " + player.getName() + " - 마지막 활성화된 1회용 인벤세이브 사용 (레벨 " + player.getLevel() + " 포함)");
            player.sendMessage(ChatColor.LIGHT_PURPLE + "💎 마지막 활성화된 1회용 인벤세이브권이 사용되었습니다! (레벨 포함)");
        }
    }

    /**
     * 인벤토리에 있는 1회용 인벤세이브권 자동 사용 (레벨 백업 추가)
     */
    private void processInventoryOneTimeInventorySave(Player player, PlayerDeathEvent event) {
        UUID playerId = player.getUniqueId();

        // 죽기 직전에 인벤토리 백업
        ItemStack[] deathInventory = new ItemStack[player.getInventory().getSize()];
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            ItemStack item = player.getInventory().getItem(i);
            deathInventory[i] = item != null ? item.clone() : null;
        }

        // 백업에서 인벤세이브권 1개 제거
        removeOneTimeInventorySaveItem(deathInventory);

        // === 레벨 및 경험치 백업 추가 ===
        jackpotPlayersBackup.put(playerId, deathInventory);

        // 레벨 정보 저장
        Map<String, Object> levelData = new HashMap<>();
        levelData.put("level", player.getLevel());
        levelData.put("exp", player.getExp());
        levelData.put("totalExp", player.getTotalExperience());
        jackpotPlayersLevelBackup.put(playerId, levelData);

        // 드롭 아이템 완전 제거
        event.getDrops().clear();
        event.setDroppedExp(0); // 경험치 드롭도 방지
        player.getInventory().clear();

        getLogger().info("💎 " + player.getName() + " - 인벤토리 1회용 인벤세이브권 자동 사용 (레벨 " + player.getLevel() + " 포함)");
    }

    /**
     * 플레이어가 1회용 인벤세이브권을 가지고 있는지 확인하고 총 개수 반환
     */
    private int getOneTimeInventorySaveItemCount(Player player) {
        int totalCount = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == Material.PAPER) {
                ItemMeta meta = item.getItemMeta();
                if (meta != null && meta.hasDisplayName() &&
                        meta.getDisplayName().equals(ChatColor.LIGHT_PURPLE + "💎 인벤세이브권 (1회) 💎")) {
                    totalCount += item.getAmount();
                }
            }
        }
        return totalCount;
    }

    private boolean hasOneTimeInventorySaveItem(Player player) {
        return getOneTimeInventorySaveItemCount(player) > 0;
    }

    /**
     * 인벤토리 백업에서 1회용 인벤세이브권 1개 제거
     */
    private void removeOneTimeInventorySaveItem(ItemStack[] inventory) {
        for (int i = 0; i < inventory.length; i++) {
            ItemStack item = inventory[i];
            if (item != null && item.getType() == Material.PAPER) {
                ItemMeta meta = item.getItemMeta();
                if (meta != null && meta.hasDisplayName() &&
                        meta.getDisplayName().equals(ChatColor.LIGHT_PURPLE + "💎 인벤세이브권 (1회) 💎")) {

                    if (item.getAmount() > 1) {
                        item.setAmount(item.getAmount() - 1);
                    } else {
                        inventory[i] = null;
                    }
                    break; // 1개만 제거
                }
            }
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();

        // 우클릭이면서 종이 아이템인지 확인
        if ((event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK)
                && item != null && item.getType() == Material.PAPER) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null && meta.hasDisplayName()) {

                // 5분 시간제 인벤세이브권
                if (meta.getDisplayName().equals(ChatColor.GOLD + "✨ 인벤세이브권 (5분) ✨")) {
                    event.setCancelled(true);

                    UUID playerId = player.getUniqueId();

                    // 이미 시간제 인벤세이브가 활성화되어 있는지 확인
                    if (jackpotPlayers.contains(playerId)) {
                        player.sendMessage(ChatColor.RED + "❌ 이미 시간제 인벤세이브가 활성화되어 있습니다! ❌");
                        return;
                    }

                    // 1회용 인벤세이브가 활성화되어 있는지 확인
                    if (oneTimeInventorySavePlayers.contains(playerId)) {
                        player.sendMessage(ChatColor.RED + "❌ 1회용 인벤세이브가 활성화되어 있습니다! 먼저 사망하거나 효과를 해제하세요.");
                        return;
                    }

                    // 아이템 1개 제거
                    if (item.getAmount() > 1) {
                        item.setAmount(item.getAmount() - 1);
                    } else {
                        player.getInventory().setItemInMainHand(null);
                    }

                    // 5분 시간제 인벤세이브 활성화
                    activateInventorySave(player);
                }

                // 1회용 인벤세이브권
                else if (meta.getDisplayName().equals(ChatColor.LIGHT_PURPLE + "💎 인벤세이브권 (1회) 💎")) {
                    event.setCancelled(true);

                    // 중복 실행 방지 (쿨다운 추가)
                    UUID playerId = player.getUniqueId();
                    long currentTime = System.currentTimeMillis();
                    Long lastUseTime = lastItemUseTime.getOrDefault(playerId, 0L);

                    if (currentTime - lastUseTime < 1000) { // 1초 쿨다운
                        return;
                    }
                    lastItemUseTime.put(playerId, currentTime);

                    // 아이템 1개 제거
                    if (item.getAmount() > 1) {
                        item.setAmount(item.getAmount() - 1);
                    } else {
                        player.getInventory().setItemInMainHand(null);
                    }

                    // 1회용 인벤세이브 활성화
                    activateOneTimeInventorySave(player, 0); // remainingCount는 사용하지 않으므로 0
                }
            }
        }
    }

    //인벤세이브 활성화 메서드
    private void activateInventorySave(Player player) {
        UUID playerId = player.getUniqueId();

        // 기존 잭팟 효과가 있다면 취소
        if (jackpotTasks.containsKey(playerId)) {
            jackpotTasks.get(playerId).cancel();
            jackpotTasks.remove(playerId);
        }

        // 1회용 인벤세이브가 활성화되어 있다면 해제 (상호 배타적)
        if (oneTimeInventorySavePlayers.contains(playerId)) {
            oneTimeInventorySavePlayers.remove(playerId);
            player.sendMessage(ChatColor.YELLOW + "⚠️ 기존 1회용 인벤세이브가 해제되고 시간제 인벤세이브가 활성화됩니다! ⚠️");
        }

        jackpotPlayers.add(playerId);

        // 시작 시간 기록 (중요!)
        inventorySaveStartTime.put(playerId, System.currentTimeMillis());

        // Title로 인벤세이브 활성화 알림
        player.sendTitle(ChatColor.GOLD + "✨ 인벤세이브 활성화! ✨",
                ChatColor.YELLOW + "5분간 인벤토리가 보호됩니다!",
                10, 80, 20);

        player.sendMessage(ChatColor.GOLD + "✨ 시간제 인벤세이브가 활성화되었습니다! 5분간 사망 시 아이템을 잃지 않습니다!");

        // 5분 타이머 시작
        restartInventorySaveTimer(player, 300);
    }
    // 플레이어 리스폰 시 잭팟 효과가 있으면 인벤토리 복원

    // cheeze_donation_plugin.java 플레이어 로그아웃 시 낙사 방지 해제 (안전장치)

    @EventHandler
    public void onPlayerQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        // 사랑 점프 낙사 방지 해제
        if (loveFallProtection.contains(playerId)) {
            loveFallProtection.remove(playerId);
            getLogger().info("🔄 " + player.getName() + " 로그아웃으로 낙사 방지 해제");
        }
    }

    /**
     * 모히스트 서버 감지 및 로그 (새로 추가)
     */
    private void detectMohistServer() {
        try {
            String version = getServer().getVersion();
            if (version.toLowerCase().contains("mohist")) {
                getLogger().info("🔧 모히스트 서버 감지됨 - 호환 모드 활성화");
                getLogger().info("🔧 Forge 모드와 Bukkit 플러그인 하이브리드 환경");
            } else if (version.toLowerCase().contains("forge")) {
                getLogger().info("🔧 Forge 기반 서버 감지됨 - 호환 모드 활성화");
            } else {
                getLogger().info("📝 일반 Bukkit/Spigot/Paper 서버");
            }
            getLogger().info("📊 서버 버전: " + version);
        } catch (Exception e) {
            getLogger().warning("서버 타입 감지 실패: " + e.getMessage());
        }
    }

    /**
     * 닉네임 변경 명령어 처리
     */
    private boolean handleNicknameChange(CommandSender sender, String[] args) {
        if (nicknameManager == null) {
            sender.sendMessage(ChatColor.RED + "❌ 닉네임 시스템이 비활성화되어 있습니다!");
            return true;
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "이 명령어는 플레이어만 사용할 수 있습니다!");
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "사용법: /치즈설정 닉네임변경 <닉네임>");
            sender.sendMessage(ChatColor.YELLOW + "💡 닉네임 규칙: 2-16자, 한글/영문/숫자/언더스코어만 허용");
            return true;
        }

        Player player = (Player) sender;
        String newNickname = args[1];

        try {
            if (nicknameManager.setNickname(player, newNickname)) {
                sender.sendMessage(ChatColor.GREEN + "✅ 닉네임이 '" + newNickname + "'로 변경되었습니다!");
                sender.sendMessage(ChatColor.AQUA + "🎯 탭리스트, 채팅, 캐릭터 위에 적용됩니다!");
            } else {
                sender.sendMessage(ChatColor.RED + "❌ 닉네임 변경에 실패했습니다!");
                sender.sendMessage(ChatColor.YELLOW + "💡 사유: 중복 닉네임, 잘못된 형식, 또는 금지된 단어");
                sender.sendMessage(ChatColor.YELLOW + "💡 닉네임 규칙: 2-16자, 한글/영문/숫자/언더스코어만 허용");
            }
        } catch (Exception e) {
            sender.sendMessage(ChatColor.RED + "❌ 닉네임 변경 중 오류가 발생했습니다!");
            getLogger().severe("닉네임 변경 오류: " + e.getMessage());
        }

        return true;
    }

    /**
     * 닉네임 제거 명령어 처리
     */
    private boolean handleNicknameRemove(CommandSender sender) {
        if (nicknameManager == null) {
            sender.sendMessage(ChatColor.RED + "❌ 닉네임 시스템이 비활성화되어 있습니다!");
            return true;
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "이 명령어는 플레이어만 사용할 수 있습니다!");
            return true;
        }

        Player player = (Player) sender;

        try {
            String currentNick = nicknameManager.getNickname(player);

            if (currentNick != null) {
                nicknameManager.removeNickname(player);
                sender.sendMessage(ChatColor.GREEN + "✅ 닉네임이 제거되었습니다!");
                sender.sendMessage(ChatColor.AQUA + "🔄 원래 이름 '" + player.getName() + "'로 되돌아갑니다!");
            } else {
                sender.sendMessage(ChatColor.RED + "❌ 설정된 닉네임이 없습니다!");
            }
        } catch (Exception e) {
            sender.sendMessage(ChatColor.RED + "❌ 닉네임 제거 중 오류가 발생했습니다!");
            getLogger().severe("닉네임 제거 오류: " + e.getMessage());
        }

        return true;
    }

    /**
     * 닉네임 정보 조회 명령어 처리
     */
    private boolean handleNicknameInfo(CommandSender sender, String[] args) {
        if (nicknameManager == null) {
            sender.sendMessage(ChatColor.RED + "❌ 닉네임 시스템이 비활성화되어 있습니다!");
            return true;
        }

        String targetName = args.length > 1 ? args[1] : sender.getName();
        Player targetPlayer = Bukkit.getPlayer(targetName);

        if (targetPlayer == null) {
            sender.sendMessage(ChatColor.RED + "❌ 플레이어를 찾을 수 없습니다: " + targetName);
            return true;
        }

        try {
            String displayName = nicknameManager.getDisplayName(targetPlayer);
            String nickname = nicknameManager.getNickname(targetPlayer);

            sender.sendMessage(ChatColor.GOLD + "=== 닉네임 정보 ===");
            sender.sendMessage(ChatColor.WHITE + "원본 이름: " + ChatColor.YELLOW + targetPlayer.getName());
            sender.sendMessage(ChatColor.WHITE + "표시 이름: " + ChatColor.AQUA + displayName);
            sender.sendMessage(ChatColor.WHITE + "닉네임 설정: " + (nickname != null ? ChatColor.GREEN + "예" : ChatColor.RED + "아니오"));

            if (sender.hasPermission("cheeze.donation.admin")) {
                Map<String, Object> stats = nicknameManager.getStatistics();
                sender.sendMessage(ChatColor.GRAY + "=== 시스템 정보 ===");
                sender.sendMessage(ChatColor.GRAY + "전체 닉네임 수: " + stats.get("total_nicknames"));
                sender.sendMessage(ChatColor.GRAY + "온라인 닉네임 수: " + stats.get("online_with_nicknames"));
                sender.sendMessage(ChatColor.GRAY + "서버 타입: " + stats.get("server_type"));
                sender.sendMessage(ChatColor.GRAY + "모히스트 호환: " + (nicknameManager.isMohistCompatible() ? "✅" : "❌"));
            }
        } catch (Exception e) {
            sender.sendMessage(ChatColor.RED + "❌ 닉네임 정보 조회 중 오류가 발생했습니다!");
            getLogger().severe("닉네임 정보 조회 오류: " + e.getMessage());
        }

        return true;
    }

    /**
     * 관리자가 다른 플레이어의 닉네임을 설정하는 명령어 처리
     */
    private boolean handleAdminSetNickname(CommandSender sender, String[] args) {
        if (nicknameManager == null) {
            sender.sendMessage(ChatColor.RED + "❌ 닉네임 시스템이 비활성화되어 있습니다!");
            return true;
        }

        if (!sender.hasPermission("cheeze.donation.nickname.others")) {
            sender.sendMessage(ChatColor.RED + "❌ 다른 플레이어의 닉네임을 설정할 권한이 없습니다!");
            return true;
        }

        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "사용법: /치즈설정 닉네임지정 <플레이어> <닉네임>");
            sender.sendMessage(ChatColor.YELLOW + "💡 예시: /치즈설정 닉네임지정 Steve 카츠님");
            return true;
        }

        String targetName = args[1];
        String newNickname = args[2];

        Player targetPlayer = Bukkit.getPlayer(targetName);
        if (targetPlayer == null) {
            sender.sendMessage(ChatColor.RED + "❌ 플레이어를 찾을 수 없습니다: " + targetName);
            return true;
        }

        try {
            if (nicknameManager.setNickname(targetPlayer, newNickname)) {
                sender.sendMessage(ChatColor.GREEN + "✅ " + targetPlayer.getName() + "의 닉네임을 '" + newNickname + "'로 설정했습니다!");

                // 대상 플레이어에게도 알림
                targetPlayer.sendMessage(ChatColor.AQUA + "🎯 관리자가 당신의 닉네임을 '" + newNickname + "'로 설정했습니다!");
                targetPlayer.sendMessage(ChatColor.GRAY + "설정자: " + sender.getName());

                // 로그 기록
                getLogger().info("관리자 " + sender.getName() + "이(가) " + targetPlayer.getName() + "의 닉네임을 '" + newNickname + "'로 설정");

            } else {
                sender.sendMessage(ChatColor.RED + "❌ 닉네임 설정에 실패했습니다!");
                sender.sendMessage(ChatColor.YELLOW + "💡 사유: 중복 닉네임, 잘못된 형식, 또는 금지된 단어");
            }
        } catch (Exception e) {
            sender.sendMessage(ChatColor.RED + "❌ 닉네임 설정 중 오류가 발생했습니다!");
            getLogger().severe("관리자 닉네임 설정 오류: " + e.getMessage());
        }

        return true;
    }

    /**
     * 관리자가 다른 플레이어의 닉네임을 제거하는 명령어 처리
     */
    private boolean handleAdminRemoveNickname(CommandSender sender, String[] args) {
        if (nicknameManager == null) {
            sender.sendMessage(ChatColor.RED + "❌ 닉네임 시스템이 비활성화되어 있습니다!");
            return true;
        }

        if (!sender.hasPermission("cheeze.donation.nickname.others")) {
            sender.sendMessage(ChatColor.RED + "❌ 다른 플레이어의 닉네임을 제거할 권한이 없습니다!");
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "사용법: /치즈설정 닉네임삭제 <플레이어>");
            sender.sendMessage(ChatColor.YELLOW + "💡 예시: /치즈설정 닉네임삭제 Steve");
            return true;
        }

        String targetName = args[1];
        Player targetPlayer = Bukkit.getPlayer(targetName);

        if (targetPlayer == null) {
            sender.sendMessage(ChatColor.RED + "❌ 플레이어를 찾을 수 없습니다: " + targetName);
            return true;
        }

        try {
            String currentNick = nicknameManager.getNickname(targetPlayer);

            if (currentNick != null) {
                nicknameManager.removeNickname(targetPlayer);
                sender.sendMessage(ChatColor.GREEN + "✅ " + targetPlayer.getName() + "의 닉네임을 제거했습니다!");
                sender.sendMessage(ChatColor.AQUA + "🔄 원래 이름 '" + targetPlayer.getName() + "'로 되돌아갑니다!");

                // 대상 플레이어에게도 알림
                targetPlayer.sendMessage(ChatColor.YELLOW + "⚠️ 관리자가 당신의 닉네임을 제거했습니다!");
                targetPlayer.sendMessage(ChatColor.AQUA + "🔄 원래 이름 '" + targetPlayer.getName() + "'로 되돌아갑니다!");
                targetPlayer.sendMessage(ChatColor.GRAY + "제거자: " + sender.getName());

                // 로그 기록
                getLogger().info("관리자 " + sender.getName() + "이(가) " + targetPlayer.getName() + "의 닉네임 '" + currentNick + "'를 제거");

            } else {
                sender.sendMessage(ChatColor.RED + "❌ " + targetPlayer.getName() + "에게 설정된 닉네임이 없습니다!");
            }
        } catch (Exception e) {
            sender.sendMessage(ChatColor.RED + "❌ 닉네임 제거 중 오류가 발생했습니다!");
            getLogger().severe("관리자 닉네임 제거 오류: " + e.getMessage());
        }

        return true;
    }

    /**
     * 모든 닉네임 목록 보기 (관리자 전용)
     */
    private boolean handleNicknameList(CommandSender sender) {
        if (nicknameManager == null) {
            sender.sendMessage(ChatColor.RED + "❌ 닉네임 시스템이 비활성화되어 있습니다!");
            return true;
        }

        if (!sender.hasPermission("cheeze.donation.admin")) {
            sender.sendMessage(ChatColor.RED + "❌ 닉네임 목록을 볼 권한이 없습니다!");
            return true;
        }

        try {
            Map<String, Object> stats = nicknameManager.getStatistics();
            int totalNicknames = (int) stats.get("total_nicknames");
            int onlineNicknames = (int) stats.get("online_with_nicknames");

            sender.sendMessage(ChatColor.GOLD + "=== 닉네임 목록 ===");
            sender.sendMessage(ChatColor.WHITE + "전체: " + ChatColor.YELLOW + totalNicknames + "개" +
                    ChatColor.WHITE + " | 온라인: " + ChatColor.GREEN + onlineNicknames + "개");
            sender.sendMessage(ChatColor.AQUA + "=== 온라인 플레이어 ===");

            int count = 0;
            for (Player player : Bukkit.getOnlinePlayers()) {
                String nickname = nicknameManager.getNickname(player);
                if (nickname != null) {
                    count++;
                    sender.sendMessage(ChatColor.WHITE + String.valueOf(count) + ". " +
                            ChatColor.YELLOW + player.getName() +
                            ChatColor.WHITE + " → " +
                            ChatColor.AQUA + nickname);
                }
            }

            if (count == 0) {
                sender.sendMessage(ChatColor.GRAY + "온라인 플레이어 중 닉네임이 설정된 플레이어가 없습니다.");
            }

            // 오프라인 플레이어도 표시 (최대 10개)
            if (totalNicknames > onlineNicknames) {
                sender.sendMessage(ChatColor.AQUA + "=== 오프라인 플레이어 (최대 10개) ===");
                int offlineCount = 0;

                for (Map.Entry<UUID, String> entry : nicknameManager.getAllNicknames().entrySet()) {
                    if (offlineCount >= 10) break;

                    Player player = Bukkit.getPlayer(entry.getKey());
                    if (player == null || !player.isOnline()) {
                        offlineCount++;
                        String playerName = Bukkit.getOfflinePlayer(entry.getKey()).getName();
                        sender.sendMessage(ChatColor.GRAY + String.valueOf(offlineCount) + ". " +
                                ChatColor.DARK_GRAY + playerName +
                                ChatColor.GRAY + " → " +
                                ChatColor.DARK_AQUA + entry.getValue());
                    }
                }

                if (totalNicknames - onlineNicknames > 10) {
                    sender.sendMessage(ChatColor.GRAY + "... 및 " + (totalNicknames - onlineNicknames - 10) + "개 더");
                }
            }

        } catch (Exception e) {
            sender.sendMessage(ChatColor.RED + "❌ 닉네임 목록 조회 중 오류가 발생했습니다!");
            getLogger().severe("닉네임 목록 조회 오류: " + e.getMessage());
        }

        return true;
    }

    /**
     * 플레이어의 표시용 닉네임 가져오기 (한글 닉네임 우선)
     */
    private String getPlayerDisplayName(Player player) {
        if (nicknameManager != null) {
            return nicknameManager.getDisplayName(player);
        }
        return player.getName();
    }

    /**
     * 플레이어 리스폰 시 감옥 상태 확인 (기존 onPlayerRespawn 이벤트 수정)
     */
    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        // 감옥에 갇혀있는 플레이어라면 감옥으로 리스폰
        if (prisonedPlayers.containsKey(playerId)) {
            Location prisonLoc = prisonedPlayers.get(playerId);
            if (prisonLoc != null) {
                event.setRespawnLocation(prisonLoc);

                // 리스폰 후 메시지 및 게임모드 설정
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        if (player.isOnline()) {
                            player.setGameMode(org.bukkit.GameMode.ADVENTURE);
                            player.sendMessage(ChatColor.DARK_GRAY + "⛓ 죽어도 벗어날 수 없습니다 ⛓");
                            player.sendTitle(ChatColor.DARK_GRAY + "⛓ 탈출 실패 ⛓",
                                    ChatColor.WHITE + "⛓ 죽어도 돌이킬 수 없습니다! ⛓",
                                    10, 60, 20);
                        }
                    }
                }.runTaskLater(this, 5L);
            }
        }

        // === 인벤토리 백업이 있으면 복원 (레벨 복원 추가) ===
        if (jackpotPlayersBackup.containsKey(playerId)) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (player.isOnline() && jackpotPlayersBackup.containsKey(playerId)) {
                        // 1. 인벤토리 복원
                        ItemStack[] backup = jackpotPlayersBackup.get(playerId);
                        player.getInventory().setContents(backup);

                        // 2. 레벨 및 경험치 복원
                        Map<String, Object> levelData = jackpotPlayersLevelBackup.get(playerId);
                        if (levelData != null) {
                            try {
                                int level = (Integer) levelData.get("level");
                                float exp = ((Number) levelData.get("exp")).floatValue();
                                int totalExp = (Integer) levelData.get("totalExp");

                                // 레벨 복원
                                player.setLevel(level);
                                player.setExp(exp);
                                player.setTotalExperience(totalExp);

                                getLogger().info("✅ " + player.getName() + " 레벨 복원: " + level + " (경험치: " + (int) (exp * 100) + "%)");

                            } catch (Exception e) {
                                getLogger().warning("⚠️ " + player.getName() + " 레벨 복원 실패: " + e.getMessage());
                            }
                        }

                        // 3. 메시지 전송
                        if (jackpotPlayers.contains(playerId)) {
                            player.sendMessage(ChatColor.GREEN + "🎰🍀 시간제 인벤세이브 발동 🍀🎰");
                        } else {
                            player.sendMessage(ChatColor.LIGHT_PURPLE + "💎 1회용 인벤세이브권 발동 💎");
                            // 1회용 인벤세이브 사용 완료 시 백업 데이터 제거
                            jackpotPlayersBackup.remove(playerId);
                            jackpotPlayersLevelBackup.remove(playerId);
                        }
                    }
                }
            }.runTaskLater(this, 1L);
        }
    }

    //1회용 인벤세이브 메서드 (스택 지원)
    private void activateOneTimeInventorySave(Player player, int remainingCount) {
        UUID playerId = player.getUniqueId();

        // 시간제 인벤세이브가 활성화되어 있다면 해제 (상호 배타적)
        if (jackpotPlayers.contains(playerId)) {
            jackpotPlayers.remove(playerId);
            if (jackpotTasks.containsKey(playerId)) {
                jackpotTasks.get(playerId).cancel();
                jackpotTasks.remove(playerId);
            }
            player.sendMessage(ChatColor.YELLOW + "⚠️ 기존 시간제 인벤세이브가 해제되고 1회용 인벤세이브가 활성화됩니다! ⚠️");
        }

        // 1회용 인벤세이브 활성화
        oneTimeInventorySavePlayers.add(playerId);

        // 사용 중인 개수 누적 (기존 활성화된 것 + 새로 활성화한 것)
        int currentUsageCount = oneTimeInventorySaveUsageCount.getOrDefault(playerId, 0) + 1;
        oneTimeInventorySaveUsageCount.put(playerId, currentUsageCount);

        // Title로 1회용 인벤세이브 활성화 알림
        player.sendTitle(ChatColor.LIGHT_PURPLE + "💎 1회용 인벤세이브 활성화! 💎",
                ChatColor.YELLOW + String.valueOf(currentUsageCount) + "회 활성화됨",
                10, 80, 20);

        // 정확한 상태 메시지
        int totalAvailable = currentUsageCount + getOneTimeInventorySaveItemCount(player);
        player.sendMessage(ChatColor.LIGHT_PURPLE + "💎 1회용 인벤세이브가 활성화되었습니다!");
        player.sendMessage(ChatColor.YELLOW + "💎 활성화: " + currentUsageCount + "회 💎");

        // ActionBar로 지속적인 알림 (죽을 때까지 계속)
        BukkitRunnable oneTimeAlertTask = new BukkitRunnable() {
            @Override
            public void run() {
                // 플레이어가 오프라인이거나 1회용 인벤세이브가 비활성화되면 중지
                if (!oneTimeInventorySavePlayers.contains(playerId) || !player.isOnline()) {
                    this.cancel();
                    return;
                }

                // 현재 활성화된 개수와 인벤토리 개수 표시
                int activatedCount = oneTimeInventorySaveUsageCount.getOrDefault(playerId, 0);
                int inventoryCount = getOneTimeInventorySaveItemCount(player);
                String message = ChatColor.LIGHT_PURPLE + "💎 활성화: " + activatedCount + "회💎";

                player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                        net.md_5.bungee.api.chat.TextComponent.fromLegacyText(message));
            }
        };

        // 1초마다 실행하여 죽을 때까지 계속 표시
        oneTimeAlertTask.runTaskTimer(this, 0L, 20L);

        // 태스크를 추적하여 나중에 정리할 수 있도록 저장
        jackpotTasks.put(playerId, oneTimeAlertTask);

        getLogger().info("💎 " + player.getName() + " - 1회용 인벤세이브 활성화 (활성화: " + currentUsageCount + "회, 인벤토리: " + getOneTimeInventorySaveItemCount(player) + "개)");
    }
}