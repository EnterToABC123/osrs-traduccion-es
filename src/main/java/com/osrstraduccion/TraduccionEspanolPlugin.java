package com.osrstraduccion;

import com.google.gson.Gson;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import net.runelite.api.Actor;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MessageNode;
import net.runelite.api.Menu;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.OverheadTextChanged;
import net.runelite.api.events.PostMenuSort;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.Filepath;

import javax.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@PluginDescriptor(
    name = "OSRS Traducción Español",
    internalName = "osrs-traduccion-es",
    description = "Traduce OSRS al español (basado en OSRS Translate PT-BR de Walter Rezende)",
    tags = {"translate", "spanish", "espanol", "traduccion"}
)
public class TraduccionEspanolPlugin extends Plugin {
    private static final int CHATBOX_UNIVERSE = 162;
    private static final int DIALOG_MESSAGE = 229;
    private static final int CHAT_OVERLAY = 263;
    private static final int MUSIC_PLAYER = 239;
    private static final int FRIENDS_LIST = 429;
    private static final int IGNORE_LIST = 432;
    private static final int PLAYER_DESIGN = 679;
    private static final int TUTORIAL_PLAYER_EXPERIENCE = 929;
    private static final int TUTORIAL_DISPLAY_NAME = 558;
    private static final int ITEM_PREVIEW = 203;
    private static final int SKILL_GUIDE = 214;
    private static final int SKILL_GUIDE_ALTERNATE = 860;
    private static final int BOOKS_NOTES = 680;
    private static final int QUEST_SCROLL = 153;
    private static final int QUEST_JOURNAL_MINIMAP = 782;
    private static final int QUEST_JOURNAL = 119;
    private static final int ACHIEVEMENT_DIARY = 259;
    private static final int ACHIEVEMENT_DIARY_SCROLL = 741;
    private static final int CLUE_SCROLL_TEXT = InterfaceID.TRAIL_CLUETEXT;
    private static final int WELCOME_SCREEN = 378;
    private static final int SETTINGS = 134;
    private static final int BANK_PIN = 213;
    private static final int MACRO_MIME_EMOTES = 188;
    private static final int CRAFTING_GOLD = 446;
    private static final int TOPLEVEL_OSRS_STRETCH = 161;
    private static final long LOGIN_INSPECTION_WINDOW_MS = 30_000L;
    private static final int LOGIN_GROUP_SCAN_LIMIT = 900;
    private static final int LOGIN_CHILD_SCAN_LIMIT = 200;
    private static final String REMOTE_MANIFEST_URL = RemoteTranslationService.DEFAULT_MANIFEST_URL;
    private static final int REMOTE_UPDATE_INTERVAL_MINUTES = 60;
    private static final int LOCAL_TRANSLATION_POLL_SECONDS = 2;

    private static final int[] DIALOG_INTERFACES = {
        InterfaceID.CHAT_LEFT,
        InterfaceID.CHAT_RIGHT,
        InterfaceID.CHATMENU,
        InterfaceID.OBJECTBOX,
    };

    private static final int[] MAIN_TEXT_INTERFACES = {
        InterfaceID.LEVELUP_DISPLAY,
        DIALOG_MESSAGE,
    };

    private static final int[] QUEST_INTERFACES = {
        QUEST_SCROLL,
        QUEST_JOURNAL_MINIMAP,
        QUEST_JOURNAL,
    };

    private static final int[] ACHIEVEMENT_DIARY_INTERFACES = {
        ACHIEVEMENT_DIARY,
        ACHIEVEMENT_DIARY_SCROLL,
    };

    private static final int[] ITEM_INTERFACES = {
        ITEM_PREVIEW,
        BOOKS_NOTES,
    };

    private static final int[] SKILL_GUIDE_INTERFACES = {
        SKILL_GUIDE,
        SKILL_GUIDE_ALTERNATE,
    };

    private static final int[] SETTINGS_INTERFACES = {
        SETTINGS,
        BANK_PIN,
        TOPLEVEL_OSRS_STRETCH,
    };

    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern BR_TAG = Pattern.compile("(?i)<br\\s*/?>");
    private static final Pattern START_TAGS = Pattern.compile("^(<str>|<col=[^>]+>)+");
    private static final Set<String> TRIVIAL_MENU_OPTIONS = Set.of(
        "Walk here",
        "Cancel",
        "Continue"
    );
    @Inject private Client client;
    @Inject private ClientThread clientThread;
    @Inject private TraduccionEspanolConfig config;
    @Inject private Gson gson;
    @Inject private OkHttpClient httpClient;
    @Inject private EventBus eventBus;
    private final ExaminePluginBridge examinePluginBridge = new ExaminePluginBridge();
    private final TranslationRepository translationRepository = new TranslationRepository();
    private final WelcomeTranslationService welcomeTranslationService = new WelcomeTranslationService();
    private volatile TranslationState translationState = TranslationState.empty();

    private boolean skillGuideOpen;
    private boolean skillGuideNeedsTranslation;
    private int skillGuideInterfaceId = SKILL_GUIDE;
    private String lastTutorialPlayerExperienceSnapshot = "";
    private String lastChatboxUniverseSnapshot = "";
    private String lastChatOverlaySnapshot = "";
    private ScheduledExecutorService remoteUpdateScheduler;
    private ScheduledExecutorService localTranslationHotReloadScheduler;
    private volatile String localTranslationFingerprint = "";
    private RemoteTranslationService remoteTranslationService;
    private volatile MissingTranslationRecorder missingTranslationRecorder;
    private volatile Filepath dataDirectory;
    private EventBus.Subscriber menuOpenedTranslationSubscriber;
    private long loginInspectionDeadline;
    private final Set<Integer> inspectedLoginGroups = new HashSet<>();
    private final Set<Integer> loginInterfaceGroups = new HashSet<>();

    @Override
    protected void startUp() throws Exception {
        log.info("[TraduccionEspanolPlugin] startUp() chamado!");
        TraduccionEspanolConfigLocalization.start(config.translationLanguage());
        // Todos los archivos del plugin viven en su carpeta de datos de RuneLite (API Filepath).
        dataDirectory = getPluginDirectory();
        Filepath translationCacheRoot = dataDirectory.joinSegment("translations");
        log.info("Cache de traducoes remotas: {}", translationCacheRoot);
        remoteTranslationService = new RemoteTranslationService(
            httpClient,
            gson,
            translationCacheRoot
        );
        reloadTranslations();
        updateMissingTranslationRecorder();
        diag("sesion", null, "info", "plugin iniciado", null);
        menuOpenedTranslationSubscriber = eventBus.register(
            MenuOpened.class,
            event -> {
                if (config.enableMenuEntries()) {
                    // Run after Menu Entry Swapper has created its entries and
                    // submenus, but before the client renders the menu box.
                    translateMenu(client.getMenu());
                }
            },
            -1000f
        );
        if (!isLocalTranslationSource()) {
            startRemoteTranslationUpdater();
        } else {
            startLocalTranslationHotReload();
        }
    }

    @Override
    protected void shutDown() {
        log.info("[TraduccionEspanolPlugin] shutDown() chamado!");
        TraduccionEspanolConfigLocalization.stop();
        if (remoteUpdateScheduler != null) {
            remoteUpdateScheduler.shutdownNow();
            remoteUpdateScheduler = null;
        }
        stopLocalTranslationHotReload();
        stopMissingTranslationRecorder();
        stopDiagnosticJournal();
        eventBus.unregister(menuOpenedTranslationSubscriber);
        menuOpenedTranslationSubscriber = null;
        remoteTranslationService = null;
        translationState = TranslationState.empty();
        skillGuideOpen = false;
        skillGuideNeedsTranslation = false;
        skillGuideInterfaceId = SKILL_GUIDE;
        lastTutorialPlayerExperienceSnapshot = "";
        lastChatboxUniverseSnapshot = "";
        lastChatOverlaySnapshot = "";
        loginInspectionDeadline = 0L;
        inspectedLoginGroups.clear();
        loginInterfaceGroups.clear();
    }

    private void startRemoteTranslationUpdater() {
        if (remoteTranslationService == null) {
            return;
        }

        if (remoteUpdateScheduler != null) {
            remoteUpdateScheduler.shutdownNow();
        }

        remoteUpdateScheduler = Executors.newSingleThreadScheduledExecutor();
        remoteUpdateScheduler.execute(this::checkRemoteTranslationUpdate);
        remoteUpdateScheduler.scheduleWithFixedDelay(
            this::checkRemoteTranslationUpdate,
            REMOTE_UPDATE_INTERVAL_MINUTES,
            REMOTE_UPDATE_INTERVAL_MINUTES,
            TimeUnit.MINUTES
        );
    }

    private void checkRemoteTranslationUpdate() {
        if (isLocalTranslationSource()) {
            return;
        }

        try {
            String languageFolder = selectedLanguageFolder();
            RemoteTranslationService.UpdateResult result = remoteTranslationService.update(
                REMOTE_MANIFEST_URL,
                languageFolder
            );
            if (isLocalTranslationSource() || !languageFolder.equals(selectedLanguageFolder())) {
                return;
            }
            boolean sourceChanged = translationRepository.configureRemoteCacheDirectory(
                result.getActiveDirectory()
            );
            if (result.isChanged() || sourceChanged) {
                translationState = translationRepository.loadState(
                    TraduccionEspanolConfig.TranslationSource.REMOTE
                );
                if (skillGuideOpen) {
                    skillGuideNeedsTranslation = true;
                }
                log.info("Traducoes remotas atualizadas para a versao {}", result.getVersion());
            } else {
                log.debug("Traducoes remotas ja estao na versao {}", result.getVersion());
            }
        } catch (Exception e) {
            log.warn("Nao foi possivel atualizar as traducoes remotas; mantendo o cache atual", e);
        }
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if (!"osrstraduccion".equals(event.getGroup())) {
            return;
        }

        TraduccionEspanolConfig.TranslationLanguage changedLanguage = languageFromConfigValue(
            event.getNewValue()
        );
        TraduccionEspanolConfigLocalization.localize(
            changedLanguage == null ? config.translationLanguage() : changedLanguage
        );
        if ("recordMissingTranslations".equals(event.getKey())
            || "translationLanguage".equals(event.getKey())) {
            updateMissingTranslationRecorder();
        }
        if ((!"translationLanguage".equals(event.getKey())
            && !"translationSource".equals(event.getKey())
            && !"translationHotReload".equals(event.getKey())
            && !"developerMode".equals(event.getKey()))
            || remoteTranslationService == null) {
            return;
        }

        reloadTranslations();
        if (isLocalTranslationSource()) {
            startLocalTranslationHotReload();
        } else {
            stopLocalTranslationHotReload();
            if (remoteUpdateScheduler == null || remoteUpdateScheduler.isShutdown()) {
                startRemoteTranslationUpdater();
            } else {
                remoteUpdateScheduler.execute(this::checkRemoteTranslationUpdate);
            }
        }
    }

    private TraduccionEspanolConfig.TranslationLanguage languageFromConfigValue(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }

        for (TraduccionEspanolConfig.TranslationLanguage language
            : TraduccionEspanolConfig.TranslationLanguage.values()) {
            if (value.equals(language.name())
                || value.equals(language.toString())
                || value.equals(language.getRepositoryFolder())) {
                return language;
            }
        }
        return null;
    }

    private String selectedLanguageFolder() {
        return config.translationLanguage().getRepositoryFolder();
    }

    private void updateMissingTranslationRecorder() {
        stopMissingTranslationRecorder();
        if (!config.recordMissingTranslations()) {
            return;
        }
        Filepath base = dataDirectory;
        if (base == null) {
            return;
        }
        missingTranslationRecorder = new MissingTranslationRecorder(
            base.joinSegment("sem-traducao"), selectedLanguageFolder(), gson);
        log.info("Registrando textos sem traducao em {}", missingTranslationRecorder.getFile());
    }

    private volatile DiagnosticJournal diagnosticJournal;

    /** Diario de diagnóstico: solo en modo desarrollador. Se crea al primer uso. */
    private void diag(String event, String type, String status, String original, String result) {
        DiagnosticJournal journal = diagnosticJournalIfEnabled();
        if (journal != null) {
            journal.write(event, type, status, original, result);
        }
    }

    private void diagOnce(String event, String type, String status, String original, String result) {
        DiagnosticJournal journal = diagnosticJournalIfEnabled();
        if (journal != null) {
            journal.writeOnce(event, type, status, original, result);
        }
    }

    private DiagnosticJournal diagnosticJournalIfEnabled() {
        if (!config.developerMode() || dataDirectory == null) {
            return null;
        }
        DiagnosticJournal journal = diagnosticJournal;
        if (journal == null) {
            synchronized (this) {
                if (diagnosticJournal == null) {
                    diagnosticJournal = new DiagnosticJournal(dataDirectory.joinSegment("diagnostico"), gson);
                    log.info("Diario de diagnostico en {}", diagnosticJournal.getFile());
                }
                journal = diagnosticJournal;
            }
        }
        return journal;
    }

    private void stopDiagnosticJournal() {
        DiagnosticJournal journal = diagnosticJournal;
        diagnosticJournal = null;
        if (journal != null) {
            journal.shutDown();
        }
    }

    private void stopMissingTranslationRecorder() {
        MissingTranslationRecorder recorder = missingTranslationRecorder;
        missingTranslationRecorder = null;
        if (recorder != null) {
            recorder.shutDown();
        }
    }

    private void recordMissingTranslation(String fileName, String source, String text) {
        MissingTranslationRecorder recorder = missingTranslationRecorder;
        // El nombre del propio personaje (cabecera de los diálogos) no es un texto a traducir
        if (recorder != null && client.getLocalPlayer() != null
            && text != null && text.trim().equalsIgnoreCase(client.getLocalPlayer().getName())) {
            return;
        }
        if (recorder != null) {
            recorder.record(fileName, source, text);
        }
    }

    /** Nome do dicionário ao qual pertence um mapa do estado atual (para o registro). */
    private static String dictionaryFileName(Map<String, String> map, TranslationState state) {
        if (map == state.translationsSkills) return "translations_skills.json";
        if (map == state.translationsQuests) return "translations_quests.json";
        if (map == state.translationsItems) return "translations_items.json";
        if (map == state.translationsMenu) return "translations_menu.json";
        if (map == state.translationsGameMessage) return "translations_game_message.json";
        if (map == state.translationsWelcome) return "translations_welcome.json";
        if (map == state.translationsSettings) return "translations_settings.json";
        return "translations.json";
    }

    private boolean isLocalTranslationSource() {
        return config.developerMode()
            && config.translationSource() == TraduccionEspanolConfig.TranslationSource.LOCAL;
    }

    /** Carpeta fija para probar diccionarios locales: <carpeta de datos del plugin>/local. */
    private Filepath localTranslationDirectory() {
        Filepath base = dataDirectory;
        return base == null ? null : base.joinSegment("local");
    }

    private void reloadTranslations() {
        String languageFolder = selectedLanguageFolder();
        translationRepository.configureLocalDirectory(localTranslationDirectory());

        if (isLocalTranslationSource()) {
            translationState = translationRepository.loadState(
                TraduccionEspanolConfig.TranslationSource.LOCAL
            );
            stopRemoteTranslationUpdater();
            return;
        }

        translationRepository.configureRemoteCacheDirectory(
            remoteTranslationService.getActiveDirectory(languageFolder)
        );
        translationState = translationRepository.loadState(
            TraduccionEspanolConfig.TranslationSource.REMOTE
        );
    }

    private void stopRemoteTranslationUpdater() {
        if (remoteUpdateScheduler != null) {
            remoteUpdateScheduler.shutdownNow();
            remoteUpdateScheduler = null;
        }
    }

    private void startLocalTranslationHotReload() {
        stopLocalTranslationHotReload();
        if (!isLocalTranslationSource() || !config.translationHotReload()) {
            return;
        }

        localTranslationFingerprint = translationRepository.localFingerprint();
        localTranslationHotReloadScheduler = Executors.newSingleThreadScheduledExecutor();
        localTranslationHotReloadScheduler.scheduleWithFixedDelay(
            this::checkLocalTranslationHotReload,
            LOCAL_TRANSLATION_POLL_SECONDS,
            LOCAL_TRANSLATION_POLL_SECONDS,
            TimeUnit.SECONDS
        );
    }

    private void stopLocalTranslationHotReload() {
        if (localTranslationHotReloadScheduler != null) {
            localTranslationHotReloadScheduler.shutdownNow();
            localTranslationHotReloadScheduler = null;
        }
        localTranslationFingerprint = "";
    }

    private void checkLocalTranslationHotReload() {
        if (!isLocalTranslationSource() || !config.translationHotReload()) {
            return;
        }

        try {
            String fingerprint = translationRepository.localFingerprint();
            if (fingerprint.equals(localTranslationFingerprint)
                || !translationRepository.areLocalFilesValid()) {
                return;
            }

            TranslationState updatedState = translationRepository.loadState(
                TraduccionEspanolConfig.TranslationSource.LOCAL
            );
            if (!fingerprint.equals(translationRepository.localFingerprint())) {
                return;
            }

            translationState = updatedState;
            localTranslationFingerprint = fingerprint;
            if (skillGuideOpen) {
                clientThread.invokeLater(() -> skillGuideNeedsTranslation = true);
            }
            log.info("Traducoes locais recarregadas automaticamente");
        } catch (Exception e) {
            log.warn("Nao foi possivel recarregar as traducoes locais", e);
        }
    }

    @Subscribe
    public void onWidgetLoaded(WidgetLoaded event) {
        int groupId = event.getGroupId();
        log.debug("[WidgetLoaded] groupId={}", groupId);

        if (groupId == CLUE_SCROLL_TEXT) {
            if (config.enableClueScrolls()) {
                clientThread.invokeLater(() ->
                    clientThread.invokeLater(() -> translateInterface(CLUE_SCROLL_TEXT)));
            }
            return;
        }

        if (config.enableWelcome() && isLoginScreenState()) {
            loginInspectionDeadline = System.currentTimeMillis() + LOGIN_INSPECTION_WINDOW_MS;
            loginInterfaceGroups.add(groupId);
            inspectLoginGroup(groupId);
            scheduleTranslation(groupId);
            return;
        }

        if (config.enableDialogues() && contains(DIALOG_INTERFACES, groupId)) {
            scheduleTranslation(groupId);
            return;
        }

        if (contains(MAIN_TEXT_INTERFACES, groupId)) {
            scheduleTranslation(groupId);
            return;
        }

        if (config.enableGameMessages() && groupId == CHAT_OVERLAY) {
            scheduleTranslation(groupId);
            return;
        }

        if (config.enableMenuEntries()
            && (groupId == PLAYER_DESIGN
                || groupId == TUTORIAL_PLAYER_EXPERIENCE
                || groupId == TUTORIAL_DISPLAY_NAME
                || groupId == MACRO_MIME_EMOTES
                || groupId == CRAFTING_GOLD)) {
            scheduleTranslation(groupId);
            return;
        }

        if (config.enableQuestJournal() && contains(QUEST_INTERFACES, groupId)) {
            scheduleTranslation(groupId);
            return;
        }

        if (config.enableAchievementDiary() && contains(ACHIEVEMENT_DIARY_INTERFACES, groupId)) {
            scheduleTranslation(groupId);
            return;
        }

        if (config.enableItems() && contains(ITEM_INTERFACES, groupId)) {
            scheduleTranslation(groupId);
            return;
        }

        if (config.enableWelcome() && groupId == WELCOME_SCREEN) {
            scheduleTranslation(groupId);
            return;
        }

        if (config.enableSkillGuide() && contains(SKILL_GUIDE_INTERFACES, groupId)) {
            skillGuideOpen = true;
            skillGuideNeedsTranslation = true;
            skillGuideInterfaceId = groupId;
            translateInterface(groupId);
        }

        if (config.enableSettings() && contains(SETTINGS_INTERFACES, groupId)) {
            scheduleTranslation(groupId);
        }
    }

    private boolean contains(int[] values, int target) {
        for (int value : values) {
            if (value == target) {
                return true;
            }
        }
        return false;
    }

    private void scheduleTranslation(int interfaceId) {
        QuestHelperCompat.scheduleDialogTranslation(clientThread, interfaceId, () -> translateInterface(interfaceId));
    }

    private boolean isLoginScreenState() {
        GameState state = client.getGameState();
        // HOPPING no cuenta: al cambiar de mundo están visibles las interfaces del juego, no las del login,
        // y marcarlas como "login" hacía que luego se buscaran solo en el diccionario de bienvenida.
        return state == GameState.LOGIN_SCREEN
            || state == GameState.LOGIN_SCREEN_AUTHENTICATOR
            || state == GameState.LOGGING_IN;
    }

    private void inspectLoginGroup(int groupId) {
        if (System.currentTimeMillis() > loginInspectionDeadline || inspectedLoginGroups.contains(groupId)) {
            return;
        }

        Map<Integer, String> texts = new LinkedHashMap<>();
        for (int i = 0; i < 200; i++) {
            Widget widget = client.getWidget(groupId, i);
            if (widget != null) {
                collectWidgetTexts(widget, texts);
            }
        }

        if (texts.isEmpty()) {
            return;
        }

        inspectedLoginGroups.add(groupId);
        log.debug("[LoginInspect] groupId={} textos={}", groupId, texts.size());
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        GameState state = event.getGameState();
        if (state != GameState.LOADING) {
            diag("sesion", null, "info", "estado del juego: " + state, null);
        }
        if (state == GameState.LOGGED_IN) {
            // Ya dentro del juego: las interfaces marcadas durante el login dejan de serlo. Si no, el banco,
            // las estadísticas, etc. se seguirían buscando en el diccionario de bienvenida y no se traducirían.
            loginInterfaceGroups.clear();
            inspectedLoginGroups.clear();
            loginInspectionDeadline = 0L;
            return;
        }
        if (!config.enableWelcome() || !isLoginScreenState()) {
            return;
        }

        loginInspectionDeadline = System.currentTimeMillis() + LOGIN_INSPECTION_WINDOW_MS;
        inspectedLoginGroups.clear();
        loginInterfaceGroups.clear();
        log.debug("[LoginInspect] state={}", state);
        inspectVisibleLoginGroups();
    }

    @Subscribe
    public void onGameTick(GameTick event) {
        if (!config.enableWelcome() || !isLoginScreenState()) {
            return;
        }

        if (loginInspectionDeadline == 0L || System.currentTimeMillis() > loginInspectionDeadline) {
            loginInspectionDeadline = System.currentTimeMillis() + LOGIN_INSPECTION_WINDOW_MS;
        }

        inspectVisibleLoginGroups();
    }

    private void inspectVisibleLoginGroups() {
        for (int groupId = 0; groupId <= LOGIN_GROUP_SCAN_LIMIT; groupId++) {
            if (inspectedLoginGroups.contains(groupId)) {
                continue;
            }

            boolean foundAnyWidget = false;
            for (int childId = 0; childId < LOGIN_CHILD_SCAN_LIMIT; childId++) {
                if (client.getWidget(groupId, childId) != null) {
                    foundAnyWidget = true;
                    break;
                }
            }

            if (!foundAnyWidget) {
                continue;
            }

            loginInterfaceGroups.add(groupId);
            inspectLoginGroup(groupId);
        }
    }

    private void collectWidgetTexts(Widget widget, Map<Integer, String> texts) {
        if (widget == null) {
            return;
        }

        String clean = normalizeLookupText(cleanText(widget.getText()));
        if (!clean.isEmpty() && containsLetters(clean)) {
            texts.putIfAbsent(widget.getId(), clean);
        }

        Widget[] children = widget.getChildren();
        if (children != null) {
            for (Widget child : children) {
                collectWidgetTexts(child, texts);
            }
        }

        Widget[] dynamicChildren = widget.getDynamicChildren();
        if (dynamicChildren != null) {
            for (Widget child : dynamicChildren) {
                collectWidgetTexts(child, texts);
            }
        }

        Widget[] nestedChildren = widget.getNestedChildren();
        if (nestedChildren != null) {
            for (Widget child : nestedChildren) {
                collectWidgetTexts(child, texts);
            }
        }
    }

    private String buildWidgetTextSnapshot(int interfaceId) {
        Widget root = client.getWidget(interfaceId, 0);
        if (root == null || root.isHidden()) {
            return "";
        }

        Map<Integer, String> texts = new LinkedHashMap<>();
        collectWidgetTexts(root, texts);
        if (texts.isEmpty()) {
            return "";
        }

        StringBuilder snapshot = new StringBuilder();
        for (Map.Entry<Integer, String> entry : texts.entrySet()) {
            snapshot.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }
        return snapshot.toString();
    }

    private boolean containsLetters(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isLetter(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    @Subscribe(priority = 1000f)
    public void onMenuOptionClicked(MenuOptionClicked event) {
        examinePluginBridge.restoreEnglishOption(event, this);

        if (config.enableSettings()) {
            Widget bankPinRoot = client.getWidget(BANK_PIN, 0);
            if (bankPinRoot != null && !bankPinRoot.isHidden()) {
                clientThread.invokeLater(() -> clientThread.invokeLater(() -> translateInterface(BANK_PIN)));
            }
        }

        if (!skillGuideOpen || !config.enableSkillGuide()) {
            return;
        }

        String option = event.getMenuOption();
        log.debug("[DEBUG MenuOptionClicked] option='{}' target='{}' action={}",
            option, event.getMenuTarget(), event.getMenuAction());
        String englishOption = reverseTranslateMenuOption(option);
        if ("View".equals(option) || "View".equals(englishOption)) {
            skillGuideNeedsTranslation = true;
            translateInterface(skillGuideInterfaceId);
        }
    }

    @Subscribe
    public void onBeforeRender(BeforeRender event) {
        suppressMouseHighlightTooltipForTranslatedMenuOptions();
        translateDynamicInterfaces();

        if (!skillGuideOpen || !config.enableSkillGuide()) {
            return;
        }

        int visibleSkillGuide = findVisibleInterface(SKILL_GUIDE_INTERFACES);
        if (visibleSkillGuide < 0) {
            skillGuideOpen = false;
            skillGuideNeedsTranslation = false;
            skillGuideInterfaceId = SKILL_GUIDE;
            return;
        }
        skillGuideInterfaceId = visibleSkillGuide;

        if (skillGuideNeedsTranslation) {
            skillGuideNeedsTranslation = false;
            translateInterface(skillGuideInterfaceId);
        }
    }

    private int findVisibleInterface(int[] interfaceIds) {
        for (int interfaceId : interfaceIds) {
            Widget root = client.getWidget(interfaceId, 0);
            if (root != null && !root.isHidden()) {
                return interfaceId;
            }
        }
        return -1;
    }

    private void translateDynamicInterfaces() {
        if (config.enableMenuEntries()) {
            String tutorialSnapshot = buildWidgetTextSnapshot(TUTORIAL_PLAYER_EXPERIENCE);
            String displayNameSnapshot = buildWidgetTextSnapshot(TUTORIAL_DISPLAY_NAME);
            String snapshot = !displayNameSnapshot.isEmpty() ? displayNameSnapshot : tutorialSnapshot;

            if (!snapshot.isEmpty() && !snapshot.equals(lastTutorialPlayerExperienceSnapshot)) {
                lastTutorialPlayerExperienceSnapshot = snapshot;
                if (!displayNameSnapshot.isEmpty()) {
                    translateInterface(TUTORIAL_DISPLAY_NAME);
                }
                if (!tutorialSnapshot.isEmpty()) {
                    translateInterface(TUTORIAL_PLAYER_EXPERIENCE);
                }
            }

            Widget macroMimeRoot = client.getWidget(MACRO_MIME_EMOTES, 0);
            if (macroMimeRoot != null && !macroMimeRoot.isHidden()) {
                translateInterface(MACRO_MIME_EMOTES);
            }

            Widget craftingGoldRoot = client.getWidget(CRAFTING_GOLD, 0);
            if (craftingGoldRoot != null && !craftingGoldRoot.isHidden()) {
                translateInterface(CRAFTING_GOLD);
            }
        }

        if (config.enableGameMessages()) {
            String overlaySnapshot = buildWidgetTextSnapshot(CHAT_OVERLAY);
            if (!overlaySnapshot.isEmpty() && !overlaySnapshot.equals(lastChatOverlaySnapshot)) {
                lastChatOverlaySnapshot = overlaySnapshot;
                translateInterface(CHAT_OVERLAY);
            }
        }

        if (config.enableWelcome()) {
            Widget welcomeRoot = client.getWidget(WELCOME_SCREEN, 0);
            if (welcomeRoot != null && !welcomeRoot.isHidden()) {
                translateInterface(WELCOME_SCREEN);
            }

            if (isLoginScreenState()) {
                for (int groupId : loginInterfaceGroups) {
                    if (groupId != WELCOME_SCREEN) {
                        translateInterface(groupId);
                    }
                }
            }
        }
    }

    private void suppressMouseHighlightTooltipForTranslatedMenuOptions() {
        if (!config.enableMenuEntries() || client.isMenuOpen()) {
            return;
        }

        MenuEntry[] menuEntries = client.getMenu().getMenuEntries();
        if (menuEntries == null || menuEntries.length == 0) {
            return;
        }

        MenuEntry lastEntry = menuEntries[menuEntries.length - 1];
        if (lastEntry == null) {
            return;
        }

        String englishOption = reverseTranslateMenuOption(lastEntry.getOption());
        if (englishOption == null || !TRIVIAL_MENU_OPTIONS.contains(englishOption)) {
            return;
        }

        int currentCycle = client.getGameCycle();
        int tooltipTimeout = client.getVarcIntValue(VarClientID.TOOLTIP_TIME);
        if (tooltipTimeout <= currentCycle) {
            client.setVarcIntValue(VarClientID.TOOLTIP_TIME, currentCycle + 1);
        }
    }

    private void translateInterface(int interfaceId) {
        for (int i = 0; i < 100; i++) {
            Widget widget = client.getWidget(interfaceId, i);
            if (widget != null) {
                translateWidget(widget, interfaceId);
            }
        }
    }

    private void translateWidget(Widget widget, int interfaceId) {
        if (widget == null) {
            return;
        }

        String text = widget.getText();
        if (text != null && !text.isEmpty()) {
            int lineHeightBefore = widget.getLineHeight();
            applyLineHeightFix(widget, interfaceId, lineHeightBefore);

            String translated = translateWidgetText(text, interfaceId);
            if (translated != null) {
                // Conserva los colores del original (p. ej. "360" en verde en la pantalla de bienvenida)
                widget.setText(TranslationLookupHelper.reapplyColors(text, translated));
                applyLineHeightFix(widget, interfaceId, Math.max(lineHeightBefore, widget.getLineHeight()));
                return;
            }
        }

        Widget[] children = widget.getChildren();
        if (children != null) {
            for (Widget child : children) {
                translateWidget(child, interfaceId);
            }
        }

        Widget[] dynamicChildren = widget.getDynamicChildren();
        if (dynamicChildren != null) {
            for (Widget child : dynamicChildren) {
                translateWidget(child, interfaceId);
            }
        }

        Widget[] nestedChildren = widget.getNestedChildren();
        if (nestedChildren != null) {
            for (Widget child : nestedChildren) {
                translateWidget(child, interfaceId);
            }
        }
    }

    private void applyLineHeightFix(Widget widget, int interfaceId, int referenceLineHeight) {
        if (!config.enableTextWrapFix() || widget == null || referenceLineHeight <= 18) {
            return;
        }

        // Chat/game chat widgets use their own spacing and get visually squashed if we force line height.
        if (interfaceId == CHATBOX_UNIVERSE || interfaceId == CHAT_OVERLAY) {
            return;
        }

        if (widget.getLineHeight() == 18) {
            return;
        }

        widget.setLineHeight(18);
        log.debug("[LineHeightFix] id={} {} -> 18", widget.getId(), referenceLineHeight);
    }


    private String translateWidgetText(String originalText, int interfaceId) {
        String cleanText = normalizeLookupText(cleanText(originalText));
        if (cleanText.isEmpty()) {
            return null;
        }

        String numberingPrefix = QuestHelperCompat.extractNumberingPrefix(cleanText);
        String lookup = numberingPrefix.isEmpty() ? cleanText : QuestHelperCompat.stripNumberingPrefix(cleanText);

        String specialTranslation = welcomeTranslationService.translateSpecialText(
            lookup,
            interfaceId,
            loginInterfaceGroups,
            translationState.translationsWelcome
        );
        if (specialTranslation != null) {
            return preserveStartTags(originalText) + numberingPrefix + specialTranslation;
        }

        TranslationState state = translationState;
        TranslationDomain domain = domainForInterface(interfaceId, state);
        if (domain.values.contains(lookup)) {
            return null;
        }

        String translation = TranslationLookupHelper.findTranslation(domain.translations, domain.regexPatterns, lookup);

        // La ventana del chat (162) también muestra mensajes que el juego escribe directamente en ella, sin
        // evento ChatMessage (p. ej. "You have herb boxes waiting to be collected at NMZ." al entrar).
        // Esos están en el diccionario de mensajes del juego, no en el de diálogos.
        if (translation == null && interfaceId == CHATBOX_UNIVERSE
            && !state.translationGameMessageValues.contains(lookup)) {
            translation = TranslationLookupHelper.findTranslation(
                state.translationsGameMessage, state.regexGameMessageTranslations, lookup);
        }

        // Ventanas generales: botones y etiquetas cortas ("Teleport", "Slash", "Grand Exchange", "Membership: 361
        // days left") suelen estar traducidos en los diccionarios de menús, ajustes o bienvenida.
        if (translation == null && domain.translations == state.translations
            && interfaceId != CHAT_OVERLAY && interfaceId != CHATBOX_UNIVERSE
            && interfaceId != MUSIC_PLAYER) {
            // (En el reproductor de música son títulos de canciones: "Dark", "Dream"... no se tocan)
            translation = findInOtherDictionaries(state, lookup);
        }

        // El chat (CHAT_OVERLAY y la ventana 162) también muestra mensajes de otros jugadores: esos nunca
        // se registran. Los mensajes del juego se registran en onChatMessage, filtrados por tipo.
        // Las listas de amigos e ignorados muestran nombres de otras personas: tampoco se registran.
        boolean mayShowOtherPeople = interfaceId == CHAT_OVERLAY || interfaceId == CHATBOX_UNIVERSE
            || interfaceId == FRIENDS_LIST || interfaceId == IGNORE_LIST;
        if (translation == null && !mayShowOtherPeople) {
            recordMissingTranslation(dictionaryFileName(domain.translations, state), "interfaz " + interfaceId, lookup);
        }
        if (!mayShowOtherPeople) {
            diagOnce("interfaz", interfaceId + " " + dictionaryFileName(domain.translations, state),
                translation == null ? "sin_traducir" : "traducido", originalText, translation);
        }

        if (translation != null && interfaceId == CHAT_OVERLAY) {
            translation = normalizeChatOverlayTags(translation);
        }

        return translation == null ? null : preserveStartTags(originalText) + numberingPrefix + translation;
    }

    /** Busca un texto de ventana en menús, ajustes, bienvenida y misiones (solo traducciones reales). */
    private static String findInOtherDictionaries(TranslationState state, String lookup) {
        String found = realTranslation(state.translationsMenu.get(lookup), lookup);
        if (found == null) {
            found = realTranslation(state.translationsSettings.get(lookup), lookup);
        }
        if (found == null) {
            found = realTranslation(TranslationLookupHelper.findTranslation(
                state.translationsWelcome, state.regexWelcomeTranslations, lookup), lookup);
        }
        if (found == null) {
            found = realTranslation(state.translationsQuests.get(lookup), lookup);
        }
        return found;
    }

    private static String realTranslation(String translation, String original) {
        return translation == null || translation.equals(original) ? null : translation;
    }

    private String normalizeChatOverlayTags(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        return BR_TAG.matcher(text)
            .replaceAll("\n")
            .replace("</col>", "<col=000000>");
    }

    private TranslationDomain domainForInterface(int interfaceId, TranslationState state) {
        if (contains(SKILL_GUIDE_INTERFACES, interfaceId)) {
            return new TranslationDomain(
                state.translationsSkills,
                state.translationSkillsValues,
                Collections.emptyList()
            );
        }
        if (interfaceId == QUEST_JOURNAL_MINIMAP
            || interfaceId == QUEST_JOURNAL
            || interfaceId == ACHIEVEMENT_DIARY
            || interfaceId == ACHIEVEMENT_DIARY_SCROLL) {
            return new TranslationDomain(
                state.translationsQuests,
                state.translationQuestsValues,
                Collections.emptyList()
            );
        }
        if (interfaceId == ITEM_PREVIEW || interfaceId == BOOKS_NOTES || interfaceId == CLUE_SCROLL_TEXT) {
            return new TranslationDomain(
                state.translationsItems,
                state.translationItemsValues,
                Collections.emptyList()
            );
        }
        if (interfaceId == PLAYER_DESIGN
            || interfaceId == TUTORIAL_PLAYER_EXPERIENCE
            || interfaceId == TUTORIAL_DISPLAY_NAME
            || interfaceId == MACRO_MIME_EMOTES
            || interfaceId == CRAFTING_GOLD) {
            return new TranslationDomain(state.translationsMenu, state.translationMenuValues, Collections.emptyList());
        }
        if (interfaceId == CHAT_OVERLAY) {
            return new TranslationDomain(
                state.translationsGameMessage,
                state.translationGameMessageValues,
                state.regexGameMessageTranslations
            );
        }
        if (interfaceId == WELCOME_SCREEN) {
            return new TranslationDomain(
                state.translationsWelcome,
                state.translationWelcomeValues,
                state.regexWelcomeTranslations
            );
        }
        if (loginInterfaceGroups.contains(interfaceId)) {
            return new TranslationDomain(
                state.translationsWelcome,
                state.translationWelcomeValues,
                state.regexWelcomeTranslations
            );
        }
        if (interfaceId == SETTINGS || interfaceId == BANK_PIN) {
            return new TranslationDomain(
                state.translationsSettings,
                state.translationSettingsValues,
                Collections.emptyList()
            );
        }
        return new TranslationDomain(state.translations, state.translationValues, state.regexTranslations);
    }

    @Subscribe
    public void onOverheadTextChanged(OverheadTextChanged event) {
        if (!config.enableOverhead()) {
            return;
        }

        Actor actor = event.getActor();
        String clean = normalizeLookupText(cleanText(event.getOverheadText()));
        if (clean.length() <= 3) {
            return;
        }

        // Frases automáticas del propio personaje ("Aaah, nothing like a nice cuppa tea!"): solo si están en el
        // diccionario. Nunca se tocan otros jugadores ni se registra lo que escribe el usuario.
        if (actor != null && actor == client.getLocalPlayer()) {
            String own = translateOwnScriptedSpeech(clean);
            if (own != null) {
                actor.setOverheadText(own);
            }
            return;
        }

        if (!(actor instanceof NPC)) {
            return;
        }

        String translation = TranslationLookupHelper.findTranslation(
            translationState.translationsOverhead,
            translationState.regexOverheadTranslations,
            clean
        );
        if (translation != null) {
            actor.setOverheadText(translation);
            log.debug("[OverheadTranslate] '{}' -> '{}'", clean, translation);
            diagOnce("overhead", actor.getName(), "traducido", clean, translation);
        } else if (!translationState.translationsOverhead.containsValue(clean)) {
            recordMissingTranslation("translations_overhead.json", "overhead", clean);
            diagOnce("overhead", actor.getName(), "sin_traducir", clean, null);
        }
    }

    // Menu Entry Swapper also handles PostMenuSort and matches the original
    // English option/target text. Translate only after it has completed all
    // built-in, custom, item, NPC, object, UI, and submenu swaps.
    @Subscribe(priority = -1000f)
    public void onPostMenuSort(PostMenuSort event) {
        if (!config.enableMenuEntries()) {
            return;
        }

        translateMenu(client.getMenu());
    }

    @Subscribe(priority = 1000f)
    public void onMenuOpened(MenuOpened event) {
        if (!config.enableMenuEntries()) {
            return;
        }

        // Menu Entry Swapper identifies original entries such as Examine when
        // it creates its Swap left-click/shift-click submenus. Restore those
        // labels before its MenuOpened handler runs.
        restoreEnglishMenu(client.getMenu());
    }

    private void translateMenu(Menu menu) {
        if (menu == null) {
            return;
        }

        for (MenuEntry entry : menu.getMenuEntries()) {
            translateMenuEntry(entry);

            Menu subMenu = entry.getSubMenu();
            if (subMenu != null) {
                translateMenu(subMenu);
            }
        }
    }

    private void translateMenuEntry(MenuEntry entry) {
        if (entry == null) {
            return;
        }

        String option = entry.getOption();
        if (option == null || option.isEmpty()) {
            return;
        }

        // El menú se vuelve a montar en cada fotograma: se recuerda el resultado de cada opción
        TranslationState current = translationState;
        if (menuCacheState != current) {
            menuOptionCache.clear();
            menuCacheState = current;
        }
        String cached = menuOptionCache.get(option);
        if (cached != null) {
            if (!cached.equals(option)) {
                entry.setOption(cached);
            }
            return;
        }
        String before = option;
        translateMenuEntryUncached(entry, option);
        if (menuOptionCache.size() > 5000) {
            menuOptionCache.clear();
        }
        menuOptionCache.put(before, entry.getOption() == null ? before : entry.getOption());
    }

    private final Map<String, String> menuOptionCache = new ConcurrentHashMap<>();
    private volatile TranslationState menuCacheState;

    private void translateMenuEntryUncached(MenuEntry entry, String option) {
        // El juego a veces separa palabras con espacio no separable ("Take-from Fishing")
        String spaced = option.replace(' ', ' ');
        String cleanOption = WHITESPACE.matcher(HTML_TAG.matcher(spaced).replaceAll("")).replaceAll(" ").trim();
        String translation = translationState.translationsMenu.get(cleanOption);
        if (translation == null) {
            translation = translateNumberedMenuOption(translationState.translationsMenu, cleanOption);
        }
        if (translation != null && !translation.equals(cleanOption)) {
            // Si la opción lleva colores, se cambia solo el texto; si no, la opción entera
            String replaced = spaced.replace(cleanOption, translation);
            entry.setOption(replaced.equals(spaced) ? translation : replaced);
        } else if (translation != null) {
            // En el diccionario, pero "traducida" igual que en inglés
            diagOnce("menu", null, "igual_al_ingles", cleanOption, translation);
        } else if (!translationState.translationMenuValues.contains(cleanOption)) {
            diagOnce("menu", null, "sin_traducir", option, null);
        }
    }

    private static final Pattern MENU_DASH_NUMBER = Pattern.compile("^(.+-)(\\d+)$");
    private static final Pattern MENU_PAREN_NUMBER = Pattern.compile("^(.+?) (\\(\\d+\\))$");

    /**
     * Opciones con cantidades: "Withdraw-14" usa "Withdraw-X" ("Retirar-X" -> "Retirar-14") y
     * "Edit-tags (1)" usa "Edit-tags". Sirve en los dos sentidos (también para volver al inglés).
     */
    static String translateNumberedMenuOption(Map<String, String> menu, String option) {
        Matcher dash = MENU_DASH_NUMBER.matcher(option);
        if (dash.matches()) {
            String base = menu.get(dash.group(1) + "X");
            if (base != null && base.endsWith("X")) {
                return base.substring(0, base.length() - 1) + dash.group(2);
            }
        }
        Matcher paren = MENU_PAREN_NUMBER.matcher(option);
        if (paren.matches()) {
            String base = menu.get(paren.group(1));
            if (base != null) {
                return base + " " + paren.group(2);
            }
        }
        return null;
    }

    private void restoreEnglishMenu(Menu menu) {
        if (menu == null) {
            return;
        }

        for (MenuEntry entry : menu.getMenuEntries()) {
            restoreEnglishMenuEntry(entry);

            Menu subMenu = entry.getSubMenu();
            if (subMenu != null) {
                restoreEnglishMenu(subMenu);
            }
        }
    }

    private void restoreEnglishMenuEntry(MenuEntry entry) {
        if (entry == null) {
            return;
        }

        String englishOption = reverseTranslateMenuOption(entry.getOption());
        if (englishOption != null) {
            entry.setOption(englishOption);
        }
    }

    boolean isMenuTranslationEnabled() {
        return config.enableMenuEntries();
    }

    String reverseTranslateMenuOption(String option) {
        if (option == null || option.isEmpty()) {
            return null;
        }

        String normalized = normalizeMenuLookup(option);
        if (normalized.isEmpty()) {
            return null;
        }

        String english = translationState.reverseTranslationsMenu.get(normalized);
        if (english == null) {
            // "retirar-14" -> "Withdraw-14", "editar etiquetas (1)" -> "Edit-tags (1)"
            Matcher dash = MENU_DASH_NUMBER.matcher(normalized);
            Matcher paren = MENU_PAREN_NUMBER.matcher(normalized);
            if (dash.matches()) {
                String base = translationState.reverseTranslationsMenu.get(dash.group(1) + "x");
                if (base != null && base.endsWith("X")) {
                    english = base.substring(0, base.length() - 1) + dash.group(2);
                }
            } else if (paren.matches()) {
                String base = translationState.reverseTranslationsMenu.get(paren.group(1));
                if (base != null) {
                    english = base + " " + paren.group(2);
                }
            }
        }
        if (english == null || english.equals(normalized)) {
            return null;
        }

        return english;
    }

    private String stripMenuText(String text) {
        return HTML_TAG.matcher(text).replaceAll("").trim();
    }

    private String normalizeMenuLookup(String text) {
        return stripMenuText(text).toLowerCase(Locale.ROOT);
    }

    // Plugins such as Item Charges parse the original English game message
    // synchronously. Translate only after their default-priority listeners
    // have consumed the event, matching the menu integration below.
    @Subscribe(priority = -1000f)
    public void onChatMessage(ChatMessage event) {
        if (!config.enableGameMessages()) {
            return;
        }

        // Frases automáticas del propio personaje en el chat público: solo si están en el diccionario.
        if (isOwnPublicChat(event)) {
            String own = translateOwnScriptedSpeech(cleanText(event.getMessage()));
            if (own != null) {
                MessageNode node = event.getMessageNode();
                if (node != null) {
                    node.setValue(own);
                    node.setRuneLiteFormatMessage(own);
                }
                event.setMessage(own);
                client.refreshChat();
            }
            return;
        }

        if (!shouldTranslateChatMessage(event.getType())) {
            // Modo desarrollador: anota el tipo de los mensajes que no se traducen (nunca chats de jugadores)
            if (!isPlayerChat(event.getType())) {
                diag("chat", chatDiagType(event.getType()), "ignorado", event.getMessage(), null);
            }
            return;
        }

        // Algumas mensagens chegam com códigos de cor do jogo ("@mes_hl_cya@Texto</col>").
        // Procuramos sem o código e o recolocamos na tradução para manter a cor.
        String rawMessage = event.getMessage();
        String colorPrefix = TranslationLookupHelper.leadingChatColorCodes(rawMessage);
        String clean = cleanText(TranslationLookupHelper.stripChatColorCodes(rawMessage));
        TranslationState state = translationState;
        if (clean.isEmpty() || state.translationGameMessageValues.contains(clean)) {
            return;
        }

        String translation = TranslationLookupHelper.findTranslation(
            state.translationsGameMessage,
            state.regexGameMessageTranslations,
            clean
        );
        if (translation == null && !colorPrefix.isEmpty()) {
            // Compatibilidade com entradas antigas que incluem o código de cor na chave
            translation = TranslationLookupHelper.findTranslation(
                state.translationsGameMessage,
                state.regexGameMessageTranslations,
                cleanText(rawMessage)
            );
            if (translation != null && translation.startsWith(colorPrefix)) {
                translation = translation.substring(colorPrefix.length());
            }
        }
        if (translation == null && isNpcSpeech(event.getType())) {
            translation = translateNpcSpeech(clean, state);
        }
        if (translation == null) {
            diag("chat", chatDiagType(event.getType()), "sin_traducir", rawMessage, null);
            recordMissingTranslation(
                isNpcSpeech(event.getType()) ? "translations.json" : "translations_game_message.json",
                "chat " + event.getType(), clean);
            return;
        }
        // Conserva los colores del original (números, nombres, mensajes enteros en rojo...), sean del juego
        // o de otros plugins que escriben en el chat. Se usa </col> normal: el chat lo entiende bien.
        String recolored = TranslationLookupHelper.reapplyColors(rawMessage, translation);
        boolean colorsKept = !recolored.equals(translation);
        translation = colorPrefix + recolored;

        if (!colorsKept) {
            if (shouldDeferRichChatOverlayTranslation(translation)) {
                diag("chat", chatDiagType(event.getType()), "traducido_en_ventana", rawMessage, translation);
                translateInterface(CHAT_OVERLAY);
                return;
            }
            translation = normalizeChatOverlayTags(translation);
        }

        log.debug("[ChatTranslate] type={} translatedLen={}", event.getType(), translation.length());

        MessageNode messageNode = event.getMessageNode();
        if (messageNode != null) {
            messageNode.setValue(translation);
            messageNode.setRuneLiteFormatMessage(translation);
        }
        event.setMessage(translation);
        client.refreshChat();
        diag("chat", chatDiagType(event.getType()), "traducido", rawMessage, translation);

        // Los mensajes de otros plugins de RuneLite (p. ej. Daily Task Indicator: "You have herb boxes waiting
        // to be collected at NMZ.") se añaden por ChatMessageManager, que después de este evento vuelve a
        // escribir su texto original en inglés. Se reaplica la traducción justo después, con sus colores.
        if (messageNode != null && event.getType() == ChatMessageType.CONSOLE) {
            final String formatted = translation;
            clientThread.invokeLater(() -> {
                String current = messageNode.getRuneLiteFormatMessage();
                if (current != null && !current.equals(formatted)) {
                    // RuneLite aplica los colores (<colHIGHLIGHT>...) al dibujar el chat
                    messageNode.setRuneLiteFormatMessage(formatted);
                    client.refreshChat();
                    diag("chat", "CONSOLE (reaplicado)", "traducido", current, formatted);
                }
            });
        }
    }

    /** Tipo de mensaje para el diario; marca los que llegan antes de que cargue el personaje. */
    private String chatDiagType(ChatMessageType type) {
        String name = String.valueOf(type);
        if (client.getGameState() != GameState.LOGGED_IN) {
            return name + " (" + client.getGameState() + ")";
        }
        return client.getLocalPlayer() == null ? name + " (sin personaje)" : name;
    }

    /** Busca una frase del propio personaje en los textos sobre la cabeza y luego en diálogos. */
    private String translateOwnScriptedSpeech(String text) {
        TranslationState state = translationState;
        String lookup = normalizeLookupText(text);
        String translation = TranslationLookupHelper.findTranslation(state.translationsOverhead, lookup);
        if (translation == null) {
            translation = TranslationLookupHelper.findTranslation(state.translations, lookup);
        }
        return translation;
    }

    private boolean isOwnPublicChat(ChatMessage event) {
        if (event.getType() != ChatMessageType.PUBLICCHAT || client.getLocalPlayer() == null) {
            return false;
        }
        String name = event.getName() == null ? "" : cleanText(event.getName()).replace(' ', ' ').trim();
        String own = client.getLocalPlayer().getName();
        return own != null && name.equalsIgnoreCase(own.replace(' ', ' ').trim());
    }

    /** Chats escritos por personas: nunca se registran ni se anotan. */
    private static boolean isPlayerChat(ChatMessageType type) {
        switch (type) {
            case PUBLICCHAT:
            case PRIVATECHAT:
            case PRIVATECHATOUT:
            case MODCHAT:
            case MODPRIVATECHAT:
            case FRIENDSCHAT:
            case CLAN_CHAT:
            case CLAN_GUEST_CHAT:
            case CLAN_GIM_CHAT:
            case AUTOTYPER:
            case MODAUTOTYPER:
            case TRADEREQ:
            case TRADE:
            case TRADE_SENT:
            case CHALREQ_TRADE:
            case CHALREQ_FRIENDSCHAT:
            case CHALREQ_CLANCHAT:
                return true;
            default:
                return false;
        }
    }

    private static boolean isNpcSpeech(ChatMessageType type) {
        return type == ChatMessageType.NPC_SAY || type == ChatMessageType.DIALOG;
    }

    /**
     * Lo que dice un NPC suele estar en el diccionario de diálogos o en el de textos sobre la cabeza,
     * no en el de mensajes del juego. Los mensajes DIALOG llegan como "Nombre|texto": se traduce solo el texto.
     */
    private String translateNpcSpeech(String text, TranslationState state) {
        String prefix = "";
        String body = text;
        int bar = text.indexOf('|');
        if (bar > 0 && bar < 40) {
            prefix = text.substring(0, bar + 1);
            body = text.substring(bar + 1).trim();
        }
        String lookup = normalizeLookupText(body);
        if (state.translationValues.contains(lookup) || state.translationsOverhead.containsValue(lookup)) {
            return null; // ya está en español
        }
        String translation = TranslationLookupHelper.findTranslation(
            state.translations, state.regexTranslations, lookup);
        if (translation == null) {
            translation = TranslationLookupHelper.findTranslation(
                state.translationsOverhead, state.regexOverheadTranslations, lookup);
        }
        return translation == null ? null : prefix + translation;
    }

    private boolean shouldDeferRichChatOverlayTranslation(String translation) {
        if (translation == null) {
            return false;
        }

        if (!containsRichOverlayFormatting(translation)) {
            return false;
        }

        return !buildWidgetTextSnapshot(CHAT_OVERLAY).isEmpty();
    }

    private boolean containsRichOverlayFormatting(String text) {
        return text.contains("<br") || text.contains("</col>") || text.contains("\n");
    }

    private boolean shouldTranslateChatMessage(ChatMessageType type) {
        if (type == null) {
            return false;
        }

        switch (type) {
            case GAMEMESSAGE:
            case LEVELUPMESSAGE:
            case ENGINE:
            case SPAM:
            case MESBOX:
            case CONSOLE:
            case DIDYOUKNOW:
            case LOGINLOGOUTNOTIFICATION:
            case ITEM_EXAMINE:
            case NPC_EXAMINE:
            case OBJECT_EXAMINE:
            // NPCs hablando en el chat ("Eyatlalli: ...") y el historial de diálogos
            case NPC_SAY:
            case DIALOG:
                return true;
            case WELCOME:
                return config.enableWelcome();
            default:
                return false;
        }
    }

    private String cleanText(String text) {
        return TranslationLookupHelper.cleanText(text);
    }

    private String normalizeLookupText(String text) {
        return TranslationLookupHelper.normalizeLookupText(text);
    }

    private String preserveStartTags(String text) {
        Matcher matcher = START_TAGS.matcher(text);
        return matcher.find() ? matcher.group() : "";
    }

    private static final class TranslationDomain {
        private final Map<String, String> translations;
        private final Set<String> values;
        private final List<TranslationLookupHelper.PatternEntry> regexPatterns;

        private TranslationDomain(
            Map<String, String> translations,
            Set<String> values,
            List<TranslationLookupHelper.PatternEntry> regexPatterns
        ) {
            this.translations = translations;
            this.values = values;
            this.regexPatterns = regexPatterns;
        }
    }

    private static final class TranslationState {
        private final Map<String, String> translations;
        private final Map<String, String> translationsSkills;
        private final Map<String, String> translationsQuests;
        private final Map<String, String> translationsItems;
        private final Map<String, String> translationsMenu;
        private final Map<String, String> translationsOverhead;
        private final Map<String, String> translationsGameMessage;
        private final Map<String, String> translationsWelcome;
        private final Map<String, String> translationsSettings;
        private final Set<String> translationValues;
        private final Set<String> translationSkillsValues;
        private final Set<String> translationQuestsValues;
        private final Set<String> translationItemsValues;
        private final Set<String> translationMenuValues;
        private final Set<String> translationGameMessageValues;
        private final Set<String> translationWelcomeValues;
        private final Set<String> translationSettingsValues;
        private final List<TranslationLookupHelper.PatternEntry> regexTranslations;
        private final List<TranslationLookupHelper.PatternEntry> regexOverheadTranslations;
        private final List<TranslationLookupHelper.PatternEntry> regexGameMessageTranslations;
        private final List<TranslationLookupHelper.PatternEntry> regexWelcomeTranslations;
        private final Map<String, String> reverseTranslationsMenu;

        private TranslationState(
            Map<String, String> translations,
            Map<String, String> translationsSkills,
            Map<String, String> translationsQuests,
            Map<String, String> translationsItems,
            Map<String, String> translationsMenu,
            Map<String, String> translationsOverhead,
            Map<String, String> translationsGameMessage,
            Map<String, String> translationsWelcome,
            Map<String, String> translationsSettings,
            Set<String> translationValues,
            Set<String> translationSkillsValues,
            Set<String> translationQuestsValues,
            Set<String> translationItemsValues,
            Set<String> translationMenuValues,
            Set<String> translationGameMessageValues,
            Set<String> translationWelcomeValues,
            Set<String> translationSettingsValues,
            List<TranslationLookupHelper.PatternEntry> regexTranslations,
            List<TranslationLookupHelper.PatternEntry> regexOverheadTranslations,
            List<TranslationLookupHelper.PatternEntry> regexGameMessageTranslations,
            List<TranslationLookupHelper.PatternEntry> regexWelcomeTranslations,
            Map<String, String> reverseTranslationsMenu
        ) {
            this.translations = translations;
            this.translationsSkills = translationsSkills;
            this.translationsQuests = translationsQuests;
            this.translationsItems = translationsItems;
            this.translationsMenu = translationsMenu;
            this.translationsOverhead = translationsOverhead;
            this.translationsGameMessage = translationsGameMessage;
            this.translationsWelcome = translationsWelcome;
            this.translationsSettings = translationsSettings;
            this.translationValues = translationValues;
            this.translationSkillsValues = translationSkillsValues;
            this.translationQuestsValues = translationQuestsValues;
            this.translationItemsValues = translationItemsValues;
            this.translationMenuValues = translationMenuValues;
            this.translationGameMessageValues = translationGameMessageValues;
            this.translationWelcomeValues = translationWelcomeValues;
            this.translationSettingsValues = translationSettingsValues;
            this.regexTranslations = regexTranslations;
            this.regexOverheadTranslations = regexOverheadTranslations;
            this.regexGameMessageTranslations = regexGameMessageTranslations;
            this.regexWelcomeTranslations = regexWelcomeTranslations;
            this.reverseTranslationsMenu = reverseTranslationsMenu;
        }

        private static TranslationState empty() {
            return new TranslationState(
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptySet(),
                Collections.emptySet(),
                Collections.emptySet(),
                Collections.emptySet(),
                Collections.emptySet(),
                Collections.emptySet(),
                Collections.emptySet(),
                Collections.emptySet(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyMap()
            );
        }
    }

    private static final class TranslationRepository {
        private static final String TRANSLATIONS = "translations.json";
        private static final String TRANSLATIONS_SKILLS = "translations_skills.json";
        private static final String TRANSLATIONS_QUESTS = "translations_quests.json";
        private static final String TRANSLATIONS_ITEMS = "translations_items.json";
        private static final String TRANSLATIONS_MENU = "translations_menu.json";
        private static final String TRANSLATIONS_OVERHEAD = "translations_overhead.json";
        private static final String TRANSLATIONS_GAME_MESSAGE = "translations_game_message.json";
        private static final String TRANSLATIONS_WELCOME = "translations_welcome.json";
        private static final String TRANSLATIONS_SETTINGS = "translations_settings.json";

        private volatile Filepath remoteCacheDirectory;
        private volatile Filepath localTranslationDirectory;

        private boolean configureRemoteCacheDirectory(Filepath directory) {
            Filepath previous = remoteCacheDirectory;
            remoteCacheDirectory = directory;
            return previous == null ? directory != null : !previous.equals(directory);
        }

        private void configureLocalDirectory(Filepath directory) {
            localTranslationDirectory = directory;
        }

        private String localFingerprint() {
            Filepath directory = localTranslationDirectory;
            StringBuilder fingerprint = new StringBuilder();
            for (String fileName : RemoteTranslationService.REQUIRED_FILES) {
                Filepath file = directory == null ? null : directory.joinSegment(fileName);
                if (file == null || !file.isFile()) {
                    fingerprint.append(fileName).append(":missing;");
                    continue;
                }
                try {
                    fingerprint.append(fileName)
                        .append(':').append(file.size())
                        .append(':').append(file.getLastModifiedTime().toMillis())
                        .append(';');
                } catch (IOException e) {
                    fingerprint.append(fileName).append(":error;");
                }
            }
            return fingerprint.toString();
        }

        private boolean areLocalFilesValid() {
            Filepath directory = localTranslationDirectory;
            if (directory == null) {
                return true;
            }

            for (String fileName : RemoteTranslationService.REQUIRED_FILES) {
                Filepath file = directory.joinSegment(fileName);
                if (!file.isFile()) {
                    continue;
                }

                try (InputStream input = file.openInputStream()) {
                    TranslationLookupHelper.parseJsonMapStrict(input);
                } catch (Exception e) {
                    log.warn("Aguardando a conclusao da edicao do arquivo local: {}", file);
                    return false;
                }
            }
            return true;
        }

        private TranslationState loadState(TraduccionEspanolConfig.TranslationSource source) {
            Map<String, String> translations = loadMap(TRANSLATIONS, source);
            Map<String, String> translationsSkills = loadMap(TRANSLATIONS_SKILLS, source);
            Map<String, String> translationsQuests = loadMap(TRANSLATIONS_QUESTS, source);
            Map<String, String> translationsItems = loadMap(TRANSLATIONS_ITEMS, source);
            Map<String, String> translationsMenu = loadMap(TRANSLATIONS_MENU, source);
            Map<String, String> translationsOverhead = loadMap(TRANSLATIONS_OVERHEAD, source);
            Map<String, String> translationsGameMessage = loadMap(TRANSLATIONS_GAME_MESSAGE, source);
            Map<String, String> translationsWelcome = loadMap(TRANSLATIONS_WELCOME, source);
            Map<String, String> translationsSettings = loadMap(TRANSLATIONS_SETTINGS, source);

            TranslationState state = new TranslationState(
                translations,
                translationsSkills,
                translationsQuests,
                translationsItems,
                translationsMenu,
                translationsOverhead,
                translationsGameMessage,
                translationsWelcome,
                translationsSettings,
                new HashSet<>(translations.values()),
                new HashSet<>(translationsSkills.values()),
                new HashSet<>(translationsQuests.values()),
                new HashSet<>(translationsItems.values()),
                new HashSet<>(translationsMenu.values()),
                new HashSet<>(translationsGameMessage.values()),
                new HashSet<>(translationsWelcome.values()),
                new HashSet<>(translationsSettings.values()),
                TranslationLookupHelper.compileRegexTranslations(translations),
                TranslationLookupHelper.compileRegexTranslations(translationsOverhead),
                TranslationLookupHelper.compileRegexTranslations(translationsGameMessage),
                TranslationLookupHelper.compileRegexTranslations(translationsWelcome),
                buildReverseMenuMap(translationsMenu)
            );

            log.info(
                "Diccionarios cargados: dialogos={} skills={} quests={} items={} menu={} "
                    + "overhead={} gameMessages={} welcome={} settings={} regex={} overheadRegex={} welcomeRegex={}",
                translations.size(),
                translationsSkills.size(),
                translationsQuests.size(),
                translationsItems.size(),
                translationsMenu.size(),
                translationsOverhead.size(),
                translationsGameMessage.size(),
                translationsWelcome.size(),
                translationsSettings.size(),
                state.regexTranslations.size(),
                state.regexOverheadTranslations.size(),
                state.regexWelcomeTranslations.size()
            );
            return state;
        }

        private Map<String, String> loadMap(
            String fileName,
            TraduccionEspanolConfig.TranslationSource source
        ) {
            if (source == TraduccionEspanolConfig.TranslationSource.LOCAL) {
                return loadLocalMap(fileName);
            }

            Filepath cacheDirectory = remoteCacheDirectory;
            if (cacheDirectory == null) {
                return Collections.emptyMap();
            }

            Filepath remoteFile = cacheDirectory.joinSegment(fileName);
            if (!remoteFile.isFile()) {
                log.warn("Arquivo ausente no cache remoto: {}", fileName);
                return Collections.emptyMap();
            }

            try (InputStream input = remoteFile.openInputStream()) {
                return TranslationLookupHelper.parseJsonMap(input);
            } catch (Exception e) {
                log.warn("Cache remoto invalido para {}", fileName, e);
                return Collections.emptyMap();
            }
        }

        private Map<String, String> loadLocalMap(String fileName) {
            Filepath directory = localTranslationDirectory;
            if (directory == null || !directory.isDirectory()) {
                return Collections.emptyMap();
            }

            Filepath localFile = directory.joinSegment(fileName);
            if (!localFile.isFile()) {
                log.warn("Arquivo local de traducoes ausente: {}", localFile);
                return Collections.emptyMap();
            }

            try (InputStream input = localFile.openInputStream()) {
                return TranslationLookupHelper.parseJsonMapStrict(input);
            } catch (Exception e) {
                log.warn("Arquivo local de traducoes invalido: {}", localFile, e);
                return Collections.emptyMap();
            }
        }

        private Map<String, String> buildReverseMenuMap(Map<String, String> source) {
            Map<String, String> reverse = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : source.entrySet()) {
                String key = stripMenuText(entry.getKey());
                String value = normalizeMenuLookup(entry.getValue());
                if (key.isEmpty() || value.isEmpty()) {
                    continue;
                }
                reverse.putIfAbsent(value, key);
            }
            return reverse;
        }

        private String stripMenuText(String text) {
            return text.replaceAll("<[^>]+>", "").trim();
        }

        private String normalizeMenuLookup(String text) {
            return stripMenuText(text).toLowerCase(Locale.ROOT);
        }
    }

    @Provides
    TraduccionEspanolConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(TraduccionEspanolConfig.class);
    }

    /**
     * Faz quebra de linha justificada: distribui palavras pra cada linha ter tamanho similar.
     * @param text texto a quebrar
     * @param maxCharsPorLinha número máximo de caracteres por linha
     * @return texto com quebras de linha (\n) balanceadas
     */
    }
