# Code graph (generated)

> **This file is generated from the sources by `node scripts/code-graph.mjs` — do not edit it by hand.**
> Hand-written flows and the data model: [README.md](README.md). 한국어: [generated_kor.md](generated_kor.md)

| Graph | Size |
|---|---|
| Backend modules | 42 modules, 44 imports (excluding imports of db.js and app.js router mounts) |
| Local model-manager modules | 10 modules, 15 imports |
| Master app | 41 Kotlin files, 182 file-to-file references |
| Farmer app | 40 Kotlin files, 97 file-to-file references |
| API map | 122 backend routes, 0 app calls without a matching route |

## 1. Backend

### Backend (backend/src)

`db.js` (SQLite connection + schema) is imported by almost every module, and `app.js` mounts every `routes/*.js` at `/api/<name>` — both kinds of arrows are omitted (mount paths are in §4 API map).

```mermaid
flowchart LR
  subgraph g_backend__["."]
    backend_src_app_js["app.js"]
    backend_src_db_js["db.js"]
    backend_src_server_js["server.js"]
  end
  subgraph g_backend_lib["lib"]
    backend_src_lib_detectors_js["detectors.js"]
    backend_src_lib_household_js["household.js"]
    backend_src_lib_marketSeed_js["marketSeed.js"]
    backend_src_lib_pg_js["pg.js"]
    backend_src_lib_push_js["push.js"]
    backend_src_lib_sweeper_js["sweeper.js"]
    backend_src_lib_validate_js["validate.js"]
    backend_src_lib_wateringFactor_js["wateringFactor.js"]
  end
  subgraph g_backend_middleware["middleware"]
    backend_src_middleware_auth_js["auth.js"]
  end
  subgraph g_backend_ml["ml"]
    backend_src_ml_defs_js["defs.js"]
    backend_src_ml_ojp1_js["ojp1.js"]
    backend_src_ml_registry_js["registry.js"]
    backend_src_ml_stats_js["stats.js"]
  end
  subgraph g_backend_routes["routes"]
    backend_src_routes_admin_js["admin.js"]
    backend_src_routes_analysis_js["analysis.js"]
    backend_src_routes_commands_js["commands.js"]
    backend_src_routes_community_js["community.js"]
    backend_src_routes_crash_js["crash.js"]
    backend_src_routes_detectors_js["detectors.js"]
    backend_src_routes_diagnosis_js["diagnosis.js"]
    backend_src_routes_events_js["events.js"]
    backend_src_routes_harvests_js["harvests.js"]
    backend_src_routes_household_js["household.js"]
    backend_src_routes_live_js["live.js"]
    backend_src_routes_market_js["market.js"]
    backend_src_routes_messages_js["messages.js"]
    backend_src_routes_models_js["models.js"]
    backend_src_routes_notifications_js["notifications.js"]
    backend_src_routes_pairing_js["pairing.js"]
    backend_src_routes_payments_js["payments.js"]
    backend_src_routes_photos_js["photos.js"]
    backend_src_routes_plants_js["plants.js"]
    backend_src_routes_policy_js["policy.js"]
    backend_src_routes_report_js["report.js"]
    backend_src_routes_subscription_js["subscription.js"]
    backend_src_routes_users_js["users.js"]
    backend_src_routes_videos_js["videos.js"]
    backend_src_routes_watering_js["watering.js"]
    backend_src_routes_weather_js["weather.js"]
  end
  backend_src_db_js --> backend_src_lib_marketSeed_js
  backend_src_lib_detectors_js --> backend_src_ml_registry_js
  backend_src_lib_push_js --> backend_src_lib_household_js
  backend_src_ml_registry_js --> backend_src_ml_defs_js
  backend_src_ml_stats_js --> backend_src_ml_registry_js
  backend_src_ml_stats_js --> backend_src_lib_detectors_js
  backend_src_routes_analysis_js --> backend_src_middleware_auth_js
  backend_src_routes_commands_js --> backend_src_middleware_auth_js
  backend_src_routes_commands_js --> backend_src_lib_validate_js
  backend_src_routes_community_js --> backend_src_lib_validate_js
  backend_src_routes_detectors_js --> backend_src_middleware_auth_js
  backend_src_routes_detectors_js --> backend_src_routes_commands_js
  backend_src_routes_detectors_js --> backend_src_lib_detectors_js
  backend_src_routes_diagnosis_js --> backend_src_middleware_auth_js
  backend_src_routes_diagnosis_js --> backend_src_routes_commands_js
  backend_src_routes_events_js --> backend_src_middleware_auth_js
  backend_src_routes_events_js --> backend_src_routes_commands_js
  backend_src_routes_events_js --> backend_src_lib_push_js
  backend_src_routes_events_js --> backend_src_lib_validate_js
  backend_src_routes_household_js --> backend_src_lib_household_js
  backend_src_routes_live_js --> backend_src_middleware_auth_js
  backend_src_routes_market_js --> backend_src_lib_marketSeed_js
  backend_src_routes_market_js --> backend_src_lib_pg_js
  backend_src_routes_models_js --> backend_src_ml_registry_js
  backend_src_routes_models_js --> backend_src_ml_defs_js
  backend_src_routes_models_js --> backend_src_ml_ojp1_js
  backend_src_routes_models_js --> backend_src_ml_stats_js
  backend_src_routes_models_js --> backend_src_routes_detectors_js
  backend_src_routes_notifications_js --> backend_src_lib_household_js
  backend_src_routes_pairing_js --> backend_src_middleware_auth_js
  backend_src_routes_pairing_js --> backend_src_lib_household_js
  backend_src_routes_pairing_js --> backend_src_routes_subscription_js
  backend_src_routes_payments_js --> backend_src_lib_pg_js
  backend_src_routes_photos_js --> backend_src_middleware_auth_js
  backend_src_routes_photos_js --> backend_src_lib_household_js
  backend_src_routes_photos_js --> backend_src_routes_commands_js
  backend_src_routes_plants_js --> backend_src_lib_household_js
  backend_src_routes_plants_js --> backend_src_lib_detectors_js
  backend_src_routes_plants_js --> backend_src_routes_detectors_js
  backend_src_routes_videos_js --> backend_src_middleware_auth_js
  backend_src_routes_videos_js --> backend_src_routes_commands_js
  backend_src_routes_watering_js --> backend_src_middleware_auth_js
  backend_src_routes_watering_js --> backend_src_lib_validate_js
  backend_src_routes_weather_js --> backend_src_lib_wateringFactor_js
```

## 2. Local model-manager

### Local model-manager (ml/model-manager)

The "(shared)" group holds backend modules the local tool reuses as-is — model math (`ojp1.js`) and the built-in model definitions (`defs.js`).

```mermaid
flowchart LR
  subgraph g_mm__shared__backend_src_ml["(shared) backend/src/ml"]
    backend_src_ml_defs_js["defs.js"]
    backend_src_ml_ojp1_js["ojp1.js"]
  end
  subgraph g_mm__["."]
    ml_model_manager_server_mjs["server.mjs"]
  end
  subgraph g_mm_lib["lib"]
    ml_model_manager_lib_git_js["git.js"]
    ml_model_manager_lib_image_js["image.js"]
    ml_model_manager_lib_jobs_js["jobs.js"]
    ml_model_manager_lib_remote_js["remote.js"]
    ml_model_manager_lib_store_js["store.js"]
    ml_model_manager_lib_train_js["train.js"]
    ml_model_manager_lib_worker_js["worker.js"]
  end
  ml_model_manager_lib_git_js --> ml_model_manager_lib_store_js
  ml_model_manager_lib_jobs_js --> ml_model_manager_lib_store_js
  ml_model_manager_lib_remote_js --> ml_model_manager_lib_store_js
  ml_model_manager_lib_store_js --> backend_src_ml_defs_js
  ml_model_manager_lib_store_js --> ml_model_manager_lib_image_js
  ml_model_manager_lib_train_js --> backend_src_ml_ojp1_js
  ml_model_manager_lib_train_js --> ml_model_manager_lib_image_js
  ml_model_manager_lib_worker_js --> backend_src_ml_ojp1_js
  ml_model_manager_lib_worker_js --> ml_model_manager_lib_image_js
  ml_model_manager_lib_worker_js --> ml_model_manager_lib_train_js
  ml_model_manager_server_mjs --> ml_model_manager_lib_store_js
  ml_model_manager_server_mjs --> ml_model_manager_lib_jobs_js
  ml_model_manager_server_mjs --> ml_model_manager_lib_git_js
  ml_model_manager_server_mjs --> ml_model_manager_lib_remote_js
  ml_model_manager_server_mjs --> ml_model_manager_lib_image_js
```

## 3. Android apps

Kotlin code in the same package uses each other without imports, so **a reference to another file's top-level declaration (class / object / interface / fun) by name** counts as a dependency (comments and strings excluded).

### Master app — packages

Arrow number = number of (file → file) references from that package into files of the target package. Parentheses = file count.

```mermaid
flowchart LR
  master_p__root_["master (2)"]
  master_p_data["master.data (7)"]
  master_p_model["master.model (1)"]
  master_p_network["master.network (3)"]
  master_p_push["master.push (1)"]
  master_p_ui["master.ui (27)"]
  master_p__root_ -- 5 --> master_p_data
  master_p__root_ -- 1 --> master_p_model
  master_p__root_ -- 1 --> master_p_network
  master_p__root_ -- 1 --> master_p_push
  master_p__root_ -- 21 --> master_p_ui
  master_p_data -- 2 --> master_p_model
  master_p_network -- 1 --> master_p_model
  master_p_push -- 1 --> master_p__root_
  master_p_push -- 4 --> master_p_data
  master_p_push -- 1 --> master_p_model
  master_p_push -- 1 --> master_p_network
  master_p_push -- 2 --> master_p_ui
  master_p_ui -- 37 --> master_p_data
  master_p_ui -- 24 --> master_p_model
  master_p_ui -- 23 --> master_p_network
```

### Master app — files

★ hub files (referenced by most files — arrows omitted): `Models.kt` (referenced by 29 files), `Prefs.kt` (referenced by 13 files), `Session.kt` (referenced by 19 files), `ApiClient.kt` (referenced by 24 files), `Theme.kt` (referenced by 25 files)

```mermaid
flowchart LR
  subgraph master_s__root_["(root)"]
    android_master_app_src_main_java_com_oojoo_farm_master_MainActivity_kt["MainActivity"]
    android_master_app_src_main_java_com_oojoo_farm_master_OojooApp_kt["OojooApp"]
  end
  subgraph master_s_data["data"]
    android_master_app_src_main_java_com_oojoo_farm_master_data_AppLocale_kt["AppLocale"]
    android_master_app_src_main_java_com_oojoo_farm_master_data_Cart_kt["Cart"]
    android_master_app_src_main_java_com_oojoo_farm_master_data_FirebaseCfg_kt["FirebaseCfg"]
    android_master_app_src_main_java_com_oojoo_farm_master_data_LocationHelper_kt["LocationHelper"]
    android_master_app_src_main_java_com_oojoo_farm_master_data_Prefs_kt["Prefs ★"]
    android_master_app_src_main_java_com_oojoo_farm_master_data_ServerConfig_kt["ServerConfig"]
    android_master_app_src_main_java_com_oojoo_farm_master_data_Session_kt["Session ★"]
  end
  subgraph master_s_model["model"]
    android_master_app_src_main_java_com_oojoo_farm_master_model_Models_kt["Models ★"]
  end
  subgraph master_s_network["network"]
    android_master_app_src_main_java_com_oojoo_farm_master_network_ApiClient_kt["ApiClient ★"]
    android_master_app_src_main_java_com_oojoo_farm_master_network_ApiService_kt["ApiService"]
    android_master_app_src_main_java_com_oojoo_farm_master_network_ServerEndpoint_kt["ServerEndpoint"]
  end
  subgraph master_s_push["push"]
    android_master_app_src_main_java_com_oojoo_farm_master_push_FcmService_kt["FcmService"]
  end
  subgraph master_s_ui["ui"]
    android_master_app_src_main_java_com_oojoo_farm_master_ui_AuthScreen_kt["AuthScreen"]
    android_master_app_src_main_java_com_oojoo_farm_master_ui_CommunityScreen_kt["CommunityScreen"]
    android_master_app_src_main_java_com_oojoo_farm_master_ui_DetectorModules_kt["DetectorModules"]
    android_master_app_src_main_java_com_oojoo_farm_master_ui_DiagnosisReviewScreen_kt["DiagnosisReviewScreen"]
    android_master_app_src_main_java_com_oojoo_farm_master_ui_EventStats_kt["EventStats"]
    android_master_app_src_main_java_com_oojoo_farm_master_ui_FamilyScreen_kt["FamilyScreen"]
    android_master_app_src_main_java_com_oojoo_farm_master_ui_FarmGrid_kt["FarmGrid"]
    android_master_app_src_main_java_com_oojoo_farm_master_ui_FarmerListScreen_kt["FarmerListScreen"]
    android_master_app_src_main_java_com_oojoo_farm_master_ui_FilamentViewer_kt["FilamentViewer"]
    android_master_app_src_main_java_com_oojoo_farm_master_ui_GalleryScreen_kt["GalleryScreen"]
    android_master_app_src_main_java_com_oojoo_farm_master_ui_GrowthAlbum_kt["GrowthAlbum"]
    android_master_app_src_main_java_com_oojoo_farm_master_ui_HomeScreen_kt["HomeScreen"]
    android_master_app_src_main_java_com_oojoo_farm_master_ui_LiveCameraScreen_kt["LiveCameraScreen"]
    android_master_app_src_main_java_com_oojoo_farm_master_ui_LocalizedMessages_kt["LocalizedMessages"]
    android_master_app_src_main_java_com_oojoo_farm_master_ui_MarketScreen_kt["MarketScreen"]
    android_master_app_src_main_java_com_oojoo_farm_master_ui_MessagesScreen_kt["MessagesScreen"]
    android_master_app_src_main_java_com_oojoo_farm_master_ui_NotificationScreen_kt["NotificationScreen"]
    android_master_app_src_main_java_com_oojoo_farm_master_ui_PairingScreen_kt["PairingScreen"]
    android_master_app_src_main_java_com_oojoo_farm_master_ui_PlantDetailScreen_kt["PlantDetailScreen"]
    android_master_app_src_main_java_com_oojoo_farm_master_ui_PlantListScreen_kt["PlantListScreen"]
    android_master_app_src_main_java_com_oojoo_farm_master_ui_PlantRegistrationScreen_kt["PlantRegistrationScreen"]
    android_master_app_src_main_java_com_oojoo_farm_master_ui_ProfileScreen_kt["ProfileScreen"]
    android_master_app_src_main_java_com_oojoo_farm_master_ui_ReportScreen_kt["ReportScreen"]
    android_master_app_src_main_java_com_oojoo_farm_master_ui_RoiEditorScreen_kt["RoiEditorScreen"]
    android_master_app_src_main_java_com_oojoo_farm_master_ui_SubscriptionScreen_kt["SubscriptionScreen"]
    android_master_app_src_main_java_com_oojoo_farm_master_ui_Theme_kt["Theme ★"]
    android_master_app_src_main_java_com_oojoo_farm_master_ui_ThemeEditorScreen_kt["ThemeEditorScreen"]
  end
  android_master_app_src_main_java_com_oojoo_farm_master_MainActivity_kt --> android_master_app_src_main_java_com_oojoo_farm_master_data_AppLocale_kt
  android_master_app_src_main_java_com_oojoo_farm_master_MainActivity_kt --> android_master_app_src_main_java_com_oojoo_farm_master_push_FcmService_kt
  android_master_app_src_main_java_com_oojoo_farm_master_MainActivity_kt --> android_master_app_src_main_java_com_oojoo_farm_master_ui_AuthScreen_kt
  android_master_app_src_main_java_com_oojoo_farm_master_MainActivity_kt --> android_master_app_src_main_java_com_oojoo_farm_master_ui_CommunityScreen_kt
  android_master_app_src_main_java_com_oojoo_farm_master_MainActivity_kt --> android_master_app_src_main_java_com_oojoo_farm_master_ui_DiagnosisReviewScreen_kt
  android_master_app_src_main_java_com_oojoo_farm_master_MainActivity_kt --> android_master_app_src_main_java_com_oojoo_farm_master_ui_FamilyScreen_kt
  android_master_app_src_main_java_com_oojoo_farm_master_MainActivity_kt --> android_master_app_src_main_java_com_oojoo_farm_master_ui_FarmerListScreen_kt
  android_master_app_src_main_java_com_oojoo_farm_master_MainActivity_kt --> android_master_app_src_main_java_com_oojoo_farm_master_ui_GalleryScreen_kt
  android_master_app_src_main_java_com_oojoo_farm_master_MainActivity_kt --> android_master_app_src_main_java_com_oojoo_farm_master_ui_HomeScreen_kt
  android_master_app_src_main_java_com_oojoo_farm_master_MainActivity_kt --> android_master_app_src_main_java_com_oojoo_farm_master_ui_LiveCameraScreen_kt
  android_master_app_src_main_java_com_oojoo_farm_master_MainActivity_kt --> android_master_app_src_main_java_com_oojoo_farm_master_ui_MarketScreen_kt
  android_master_app_src_main_java_com_oojoo_farm_master_MainActivity_kt --> android_master_app_src_main_java_com_oojoo_farm_master_ui_MessagesScreen_kt
  android_master_app_src_main_java_com_oojoo_farm_master_MainActivity_kt --> android_master_app_src_main_java_com_oojoo_farm_master_ui_NotificationScreen_kt
  android_master_app_src_main_java_com_oojoo_farm_master_MainActivity_kt --> android_master_app_src_main_java_com_oojoo_farm_master_ui_PairingScreen_kt
  android_master_app_src_main_java_com_oojoo_farm_master_MainActivity_kt --> android_master_app_src_main_java_com_oojoo_farm_master_ui_PlantDetailScreen_kt
  android_master_app_src_main_java_com_oojoo_farm_master_MainActivity_kt --> android_master_app_src_main_java_com_oojoo_farm_master_ui_PlantListScreen_kt
  android_master_app_src_main_java_com_oojoo_farm_master_MainActivity_kt --> android_master_app_src_main_java_com_oojoo_farm_master_ui_PlantRegistrationScreen_kt
  android_master_app_src_main_java_com_oojoo_farm_master_MainActivity_kt --> android_master_app_src_main_java_com_oojoo_farm_master_ui_ProfileScreen_kt
  android_master_app_src_main_java_com_oojoo_farm_master_MainActivity_kt --> android_master_app_src_main_java_com_oojoo_farm_master_ui_ReportScreen_kt
  android_master_app_src_main_java_com_oojoo_farm_master_MainActivity_kt --> android_master_app_src_main_java_com_oojoo_farm_master_ui_RoiEditorScreen_kt
  android_master_app_src_main_java_com_oojoo_farm_master_MainActivity_kt --> android_master_app_src_main_java_com_oojoo_farm_master_ui_SubscriptionScreen_kt
  android_master_app_src_main_java_com_oojoo_farm_master_MainActivity_kt --> android_master_app_src_main_java_com_oojoo_farm_master_ui_ThemeEditorScreen_kt
  android_master_app_src_main_java_com_oojoo_farm_master_OojooApp_kt --> android_master_app_src_main_java_com_oojoo_farm_master_data_AppLocale_kt
  android_master_app_src_main_java_com_oojoo_farm_master_OojooApp_kt --> android_master_app_src_main_java_com_oojoo_farm_master_data_FirebaseCfg_kt
  android_master_app_src_main_java_com_oojoo_farm_master_data_Prefs_kt --> android_master_app_src_main_java_com_oojoo_farm_master_data_AppLocale_kt
  android_master_app_src_main_java_com_oojoo_farm_master_data_Prefs_kt --> android_master_app_src_main_java_com_oojoo_farm_master_data_ServerConfig_kt
  android_master_app_src_main_java_com_oojoo_farm_master_network_ApiClient_kt --> android_master_app_src_main_java_com_oojoo_farm_master_network_ApiService_kt
  android_master_app_src_main_java_com_oojoo_farm_master_network_ApiClient_kt --> android_master_app_src_main_java_com_oojoo_farm_master_network_ServerEndpoint_kt
  android_master_app_src_main_java_com_oojoo_farm_master_push_FcmService_kt --> android_master_app_src_main_java_com_oojoo_farm_master_MainActivity_kt
  android_master_app_src_main_java_com_oojoo_farm_master_push_FcmService_kt --> android_master_app_src_main_java_com_oojoo_farm_master_data_AppLocale_kt
  android_master_app_src_main_java_com_oojoo_farm_master_push_FcmService_kt --> android_master_app_src_main_java_com_oojoo_farm_master_data_FirebaseCfg_kt
  android_master_app_src_main_java_com_oojoo_farm_master_push_FcmService_kt --> android_master_app_src_main_java_com_oojoo_farm_master_ui_NotificationScreen_kt
  android_master_app_src_main_java_com_oojoo_farm_master_push_FcmService_kt --> android_master_app_src_main_java_com_oojoo_farm_master_ui_PlantDetailScreen_kt
  android_master_app_src_main_java_com_oojoo_farm_master_ui_AuthScreen_kt --> android_master_app_src_main_java_com_oojoo_farm_master_data_LocationHelper_kt
  android_master_app_src_main_java_com_oojoo_farm_master_ui_CommunityScreen_kt --> android_master_app_src_main_java_com_oojoo_farm_master_data_AppLocale_kt
  android_master_app_src_main_java_com_oojoo_farm_master_ui_CommunityScreen_kt --> android_master_app_src_main_java_com_oojoo_farm_master_data_LocationHelper_kt
  android_master_app_src_main_java_com_oojoo_farm_master_ui_CommunityScreen_kt --> android_master_app_src_main_java_com_oojoo_farm_master_ui_LocalizedMessages_kt
  android_master_app_src_main_java_com_oojoo_farm_master_ui_DetectorModules_kt --> android_master_app_src_main_java_com_oojoo_farm_master_ui_EventStats_kt
  android_master_app_src_main_java_com_oojoo_farm_master_ui_DetectorModules_kt --> android_master_app_src_main_java_com_oojoo_farm_master_ui_GrowthAlbum_kt
  android_master_app_src_main_java_com_oojoo_farm_master_ui_DiagnosisReviewScreen_kt --> android_master_app_src_main_java_com_oojoo_farm_master_data_AppLocale_kt
  android_master_app_src_main_java_com_oojoo_farm_master_ui_DiagnosisReviewScreen_kt --> android_master_app_src_main_java_com_oojoo_farm_master_ui_LocalizedMessages_kt
  android_master_app_src_main_java_com_oojoo_farm_master_ui_EventStats_kt --> android_master_app_src_main_java_com_oojoo_farm_master_ui_GrowthAlbum_kt
  android_master_app_src_main_java_com_oojoo_farm_master_ui_EventStats_kt --> android_master_app_src_main_java_com_oojoo_farm_master_ui_PlantDetailScreen_kt
  android_master_app_src_main_java_com_oojoo_farm_master_ui_FarmerListScreen_kt --> android_master_app_src_main_java_com_oojoo_farm_master_ui_FilamentViewer_kt
  android_master_app_src_main_java_com_oojoo_farm_master_ui_FarmerListScreen_kt --> android_master_app_src_main_java_com_oojoo_farm_master_ui_LocalizedMessages_kt
  android_master_app_src_main_java_com_oojoo_farm_master_ui_FilamentViewer_kt --> android_master_app_src_main_java_com_oojoo_farm_master_ui_FarmGrid_kt
  android_master_app_src_main_java_com_oojoo_farm_master_ui_GalleryScreen_kt --> android_master_app_src_main_java_com_oojoo_farm_master_ui_FilamentViewer_kt
  android_master_app_src_main_java_com_oojoo_farm_master_ui_GalleryScreen_kt --> android_master_app_src_main_java_com_oojoo_farm_master_ui_RoiEditorScreen_kt
  android_master_app_src_main_java_com_oojoo_farm_master_ui_HomeScreen_kt --> android_master_app_src_main_java_com_oojoo_farm_master_data_AppLocale_kt
  android_master_app_src_main_java_com_oojoo_farm_master_ui_HomeScreen_kt --> android_master_app_src_main_java_com_oojoo_farm_master_data_LocationHelper_kt
  android_master_app_src_main_java_com_oojoo_farm_master_ui_HomeScreen_kt --> android_master_app_src_main_java_com_oojoo_farm_master_ui_FilamentViewer_kt
  android_master_app_src_main_java_com_oojoo_farm_master_ui_LiveCameraScreen_kt --> android_master_app_src_main_java_com_oojoo_farm_master_data_AppLocale_kt
  android_master_app_src_main_java_com_oojoo_farm_master_ui_LocalizedMessages_kt --> android_master_app_src_main_java_com_oojoo_farm_master_data_AppLocale_kt
  android_master_app_src_main_java_com_oojoo_farm_master_ui_MarketScreen_kt --> android_master_app_src_main_java_com_oojoo_farm_master_data_Cart_kt
  android_master_app_src_main_java_com_oojoo_farm_master_ui_MessagesScreen_kt --> android_master_app_src_main_java_com_oojoo_farm_master_ui_LocalizedMessages_kt
  android_master_app_src_main_java_com_oojoo_farm_master_ui_NotificationScreen_kt --> android_master_app_src_main_java_com_oojoo_farm_master_ui_PlantDetailScreen_kt
  android_master_app_src_main_java_com_oojoo_farm_master_ui_PlantDetailScreen_kt --> android_master_app_src_main_java_com_oojoo_farm_master_data_AppLocale_kt
  android_master_app_src_main_java_com_oojoo_farm_master_ui_PlantDetailScreen_kt --> android_master_app_src_main_java_com_oojoo_farm_master_ui_DetectorModules_kt
  android_master_app_src_main_java_com_oojoo_farm_master_ui_PlantDetailScreen_kt --> android_master_app_src_main_java_com_oojoo_farm_master_ui_EventStats_kt
  android_master_app_src_main_java_com_oojoo_farm_master_ui_PlantDetailScreen_kt --> android_master_app_src_main_java_com_oojoo_farm_master_ui_FarmGrid_kt
  android_master_app_src_main_java_com_oojoo_farm_master_ui_PlantDetailScreen_kt --> android_master_app_src_main_java_com_oojoo_farm_master_ui_FilamentViewer_kt
  android_master_app_src_main_java_com_oojoo_farm_master_ui_PlantDetailScreen_kt --> android_master_app_src_main_java_com_oojoo_farm_master_ui_GrowthAlbum_kt
  android_master_app_src_main_java_com_oojoo_farm_master_ui_PlantDetailScreen_kt --> android_master_app_src_main_java_com_oojoo_farm_master_ui_LocalizedMessages_kt
  android_master_app_src_main_java_com_oojoo_farm_master_ui_PlantListScreen_kt --> android_master_app_src_main_java_com_oojoo_farm_master_ui_DetectorModules_kt
  android_master_app_src_main_java_com_oojoo_farm_master_ui_PlantListScreen_kt --> android_master_app_src_main_java_com_oojoo_farm_master_ui_FilamentViewer_kt
  android_master_app_src_main_java_com_oojoo_farm_master_ui_PlantListScreen_kt --> android_master_app_src_main_java_com_oojoo_farm_master_ui_LocalizedMessages_kt
  android_master_app_src_main_java_com_oojoo_farm_master_ui_PlantRegistrationScreen_kt --> android_master_app_src_main_java_com_oojoo_farm_master_ui_FarmGrid_kt
  android_master_app_src_main_java_com_oojoo_farm_master_ui_PlantRegistrationScreen_kt --> android_master_app_src_main_java_com_oojoo_farm_master_ui_FilamentViewer_kt
  android_master_app_src_main_java_com_oojoo_farm_master_ui_ProfileScreen_kt --> android_master_app_src_main_java_com_oojoo_farm_master_ui_LocalizedMessages_kt
  android_master_app_src_main_java_com_oojoo_farm_master_ui_SubscriptionScreen_kt --> android_master_app_src_main_java_com_oojoo_farm_master_ui_LocalizedMessages_kt
  android_master_app_src_main_java_com_oojoo_farm_master_ui_ThemeEditorScreen_kt --> android_master_app_src_main_java_com_oojoo_farm_master_data_AppLocale_kt
  android_master_app_src_main_java_com_oojoo_farm_master_ui_ThemeEditorScreen_kt --> android_master_app_src_main_java_com_oojoo_farm_master_network_ServerEndpoint_kt
```

### Farmer app — packages

Arrow number = number of (file → file) references from that package into files of the target package. Parentheses = file count.

```mermaid
flowchart LR
  farmer_p__root_["slave (1)"]
  farmer_p_album["slave.album (2)"]
  farmer_p_data["slave.data (4)"]
  farmer_p_detect["slave.detect (3)"]
  farmer_p_hardware["slave.hardware (4)"]
  farmer_p_model["slave.model (1)"]
  farmer_p_network["slave.network (3)"]
  farmer_p_service["slave.service (4)"]
  farmer_p_ui["slave.ui (5)"]
  farmer_p_vision["slave.vision (13)"]
  farmer_p__root_ -- 2 --> farmer_p_data
  farmer_p__root_ -- 1 --> farmer_p_network
  farmer_p__root_ -- 1 --> farmer_p_service
  farmer_p__root_ -- 5 --> farmer_p_ui
  farmer_p_detect -- 2 --> farmer_p_model
  farmer_p_detect -- 1 --> farmer_p_network
  farmer_p_detect -- 4 --> farmer_p_vision
  farmer_p_network -- 1 --> farmer_p_model
  farmer_p_service -- 1 --> farmer_p__root_
  farmer_p_service -- 1 --> farmer_p_album
  farmer_p_service -- 4 --> farmer_p_data
  farmer_p_service -- 2 --> farmer_p_detect
  farmer_p_service -- 1 --> farmer_p_hardware
  farmer_p_service -- 1 --> farmer_p_model
  farmer_p_service -- 2 --> farmer_p_network
  farmer_p_service -- 13 --> farmer_p_vision
  farmer_p_ui -- 4 --> farmer_p_data
  farmer_p_ui -- 1 --> farmer_p_hardware
  farmer_p_ui -- 2 --> farmer_p_model
  farmer_p_ui -- 4 --> farmer_p_network
  farmer_p_ui -- 4 --> farmer_p_service
  farmer_p_ui -- 3 --> farmer_p_vision
  farmer_p_vision -- 1 --> farmer_p_album
  farmer_p_vision -- 1 --> farmer_p_service
```

### Farmer app — files

★ hub files (referenced by most files — arrows omitted): none

```mermaid
flowchart LR
  subgraph farmer_s__root_["(root)"]
    android_slave_app_src_main_java_com_oojoo_farm_slave_MainActivity_kt["MainActivity"]
  end
  subgraph farmer_s_album["album"]
    android_slave_app_src_main_java_com_oojoo_farm_slave_album_GalleryPublisher_kt["GalleryPublisher"]
    android_slave_app_src_main_java_com_oojoo_farm_slave_album_PlantAlbum_kt["PlantAlbum"]
  end
  subgraph farmer_s_data["data"]
    android_slave_app_src_main_java_com_oojoo_farm_slave_data_AppLocale_kt["AppLocale"]
    android_slave_app_src_main_java_com_oojoo_farm_slave_data_LocationUtil_kt["LocationUtil"]
    android_slave_app_src_main_java_com_oojoo_farm_slave_data_Prefs_kt["Prefs"]
    android_slave_app_src_main_java_com_oojoo_farm_slave_data_ServerConfig_kt["ServerConfig"]
  end
  subgraph farmer_s_detect["detect"]
    android_slave_app_src_main_java_com_oojoo_farm_slave_detect_DetectorLogic_kt["DetectorLogic"]
    android_slave_app_src_main_java_com_oojoo_farm_slave_detect_DetectorManager_kt["DetectorManager"]
    android_slave_app_src_main_java_com_oojoo_farm_slave_detect_ModelStore_kt["ModelStore"]
  end
  subgraph farmer_s_hardware["hardware"]
    android_slave_app_src_main_java_com_oojoo_farm_slave_hardware_BleHardwareController_kt["BleHardwareController"]
    android_slave_app_src_main_java_com_oojoo_farm_slave_hardware_Hardware_kt["Hardware"]
    android_slave_app_src_main_java_com_oojoo_farm_slave_hardware_HardwareController_kt["HardwareController"]
    android_slave_app_src_main_java_com_oojoo_farm_slave_hardware_MockHardwareController_kt["MockHardwareController"]
  end
  subgraph farmer_s_model["model"]
    android_slave_app_src_main_java_com_oojoo_farm_slave_model_Models_kt["Models"]
  end
  subgraph farmer_s_network["network"]
    android_slave_app_src_main_java_com_oojoo_farm_slave_network_ApiClient_kt["ApiClient"]
    android_slave_app_src_main_java_com_oojoo_farm_slave_network_ApiService_kt["ApiService"]
    android_slave_app_src_main_java_com_oojoo_farm_slave_network_ServerEndpoint_kt["ServerEndpoint"]
  end
  subgraph farmer_s_service["service"]
    android_slave_app_src_main_java_com_oojoo_farm_slave_service_BootReceiver_kt["BootReceiver"]
    android_slave_app_src_main_java_com_oojoo_farm_slave_service_FarmerEngine_kt["FarmerEngine"]
    android_slave_app_src_main_java_com_oojoo_farm_slave_service_FarmerService_kt["FarmerService"]
    android_slave_app_src_main_java_com_oojoo_farm_slave_service_MjpegStreamer_kt["MjpegStreamer"]
  end
  subgraph farmer_s_ui["ui"]
    android_slave_app_src_main_java_com_oojoo_farm_slave_ui_DashboardScreen_kt["DashboardScreen"]
    android_slave_app_src_main_java_com_oojoo_farm_slave_ui_PairingScreen_kt["PairingScreen"]
    android_slave_app_src_main_java_com_oojoo_farm_slave_ui_RoiEditorScreen_kt["RoiEditorScreen"]
    android_slave_app_src_main_java_com_oojoo_farm_slave_ui_SettingsScreen_kt["SettingsScreen"]
    android_slave_app_src_main_java_com_oojoo_farm_slave_ui_Theme_kt["Theme"]
  end
  subgraph farmer_s_vision["vision"]
    android_slave_app_src_main_java_com_oojoo_farm_slave_vision_CameraHolder_kt["CameraHolder"]
    android_slave_app_src_main_java_com_oojoo_farm_slave_vision_CameraHost_kt["CameraHost"]
    android_slave_app_src_main_java_com_oojoo_farm_slave_vision_CameraPreview_kt["CameraPreview"]
    android_slave_app_src_main_java_com_oojoo_farm_slave_vision_GrowthClipRenderer_kt["GrowthClipRenderer"]
    android_slave_app_src_main_java_com_oojoo_farm_slave_vision_HealthGradeNet_kt["HealthGradeNet"]
    android_slave_app_src_main_java_com_oojoo_farm_slave_vision_Ojp1_kt["Ojp1"]
    android_slave_app_src_main_java_com_oojoo_farm_slave_vision_OrientationPolicy_kt["OrientationPolicy"]
    android_slave_app_src_main_java_com_oojoo_farm_slave_vision_OrientationUtil_kt["OrientationUtil"]
    android_slave_app_src_main_java_com_oojoo_farm_slave_vision_PestNet_kt["PestNet"]
    android_slave_app_src_main_java_com_oojoo_farm_slave_vision_PlantAnalyzer_kt["PlantAnalyzer"]
    android_slave_app_src_main_java_com_oojoo_farm_slave_vision_PlantHealthNet_kt["PlantHealthNet"]
    android_slave_app_src_main_java_com_oojoo_farm_slave_vision_RoiStore_kt["RoiStore"]
    android_slave_app_src_main_java_com_oojoo_farm_slave_vision_VideoFrameSampler_kt["VideoFrameSampler"]
  end
  android_slave_app_src_main_java_com_oojoo_farm_slave_MainActivity_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_data_AppLocale_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_MainActivity_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_data_Prefs_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_MainActivity_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_network_ApiClient_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_MainActivity_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_service_FarmerService_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_MainActivity_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_ui_DashboardScreen_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_MainActivity_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_ui_PairingScreen_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_MainActivity_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_ui_RoiEditorScreen_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_MainActivity_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_ui_SettingsScreen_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_MainActivity_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_ui_Theme_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_album_PlantAlbum_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_album_GalleryPublisher_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_data_AppLocale_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_data_Prefs_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_data_Prefs_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_data_AppLocale_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_data_Prefs_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_data_ServerConfig_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_detect_DetectorManager_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_detect_DetectorLogic_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_detect_DetectorManager_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_detect_ModelStore_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_detect_DetectorManager_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_model_Models_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_detect_DetectorManager_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_network_ApiClient_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_detect_DetectorManager_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_vision_HealthGradeNet_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_detect_DetectorManager_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_vision_Ojp1_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_detect_DetectorManager_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_vision_PestNet_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_detect_DetectorManager_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_vision_PlantHealthNet_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_detect_ModelStore_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_model_Models_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_hardware_BleHardwareController_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_hardware_HardwareController_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_hardware_Hardware_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_hardware_BleHardwareController_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_hardware_Hardware_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_hardware_HardwareController_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_hardware_Hardware_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_hardware_MockHardwareController_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_hardware_MockHardwareController_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_hardware_HardwareController_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_network_ApiClient_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_network_ApiService_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_network_ApiClient_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_network_ServerEndpoint_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_network_ApiService_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_model_Models_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_service_BootReceiver_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_data_Prefs_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_service_BootReceiver_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_service_FarmerService_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_service_FarmerEngine_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_album_PlantAlbum_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_service_FarmerEngine_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_data_LocationUtil_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_service_FarmerEngine_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_data_Prefs_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_service_FarmerEngine_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_detect_DetectorLogic_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_service_FarmerEngine_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_detect_DetectorManager_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_service_FarmerEngine_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_hardware_Hardware_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_service_FarmerEngine_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_model_Models_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_service_FarmerEngine_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_network_ApiClient_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_service_FarmerEngine_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_service_MjpegStreamer_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_service_FarmerEngine_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_vision_CameraHolder_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_service_FarmerEngine_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_vision_CameraHost_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_service_FarmerEngine_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_vision_GrowthClipRenderer_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_service_FarmerEngine_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_vision_HealthGradeNet_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_service_FarmerEngine_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_vision_OrientationPolicy_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_service_FarmerEngine_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_vision_OrientationUtil_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_service_FarmerEngine_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_vision_PestNet_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_service_FarmerEngine_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_vision_PlantAnalyzer_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_service_FarmerEngine_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_vision_PlantHealthNet_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_service_FarmerEngine_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_vision_RoiStore_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_service_FarmerEngine_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_vision_VideoFrameSampler_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_service_FarmerService_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_MainActivity_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_service_FarmerService_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_data_Prefs_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_service_FarmerService_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_network_ApiClient_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_service_FarmerService_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_service_FarmerEngine_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_service_FarmerService_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_vision_CameraHost_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_service_MjpegStreamer_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_vision_CameraHolder_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_ui_DashboardScreen_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_data_Prefs_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_ui_DashboardScreen_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_service_FarmerEngine_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_ui_DashboardScreen_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_service_FarmerService_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_ui_DashboardScreen_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_ui_Theme_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_ui_DashboardScreen_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_vision_CameraPreview_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_ui_PairingScreen_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_data_Prefs_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_ui_PairingScreen_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_model_Models_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_ui_PairingScreen_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_network_ApiClient_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_ui_PairingScreen_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_ui_Theme_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_ui_RoiEditorScreen_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_model_Models_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_ui_RoiEditorScreen_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_network_ApiClient_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_ui_RoiEditorScreen_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_service_FarmerEngine_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_ui_RoiEditorScreen_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_ui_Theme_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_ui_RoiEditorScreen_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_vision_CameraPreview_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_ui_RoiEditorScreen_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_vision_RoiStore_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_ui_SettingsScreen_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_data_AppLocale_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_ui_SettingsScreen_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_data_Prefs_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_ui_SettingsScreen_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_hardware_Hardware_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_ui_SettingsScreen_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_network_ApiClient_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_ui_SettingsScreen_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_network_ServerEndpoint_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_ui_SettingsScreen_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_service_FarmerService_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_ui_SettingsScreen_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_ui_Theme_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_vision_CameraHolder_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_vision_OrientationUtil_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_vision_CameraHost_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_service_FarmerEngine_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_vision_CameraHost_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_vision_CameraHolder_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_vision_CameraHost_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_vision_OrientationPolicy_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_vision_CameraHost_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_vision_OrientationUtil_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_vision_CameraHost_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_vision_PlantAnalyzer_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_vision_CameraPreview_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_vision_CameraHolder_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_vision_CameraPreview_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_vision_CameraHost_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_vision_CameraPreview_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_vision_OrientationUtil_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_vision_CameraPreview_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_vision_PlantAnalyzer_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_vision_GrowthClipRenderer_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_album_PlantAlbum_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_vision_GrowthClipRenderer_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_vision_OrientationUtil_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_vision_HealthGradeNet_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_vision_Ojp1_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_vision_PestNet_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_vision_Ojp1_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_vision_PlantAnalyzer_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_vision_PlantHealthNet_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_vision_PlantAnalyzer_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_vision_RoiStore_kt
  android_slave_app_src_main_java_com_oojoo_farm_slave_vision_PlantHealthNet_kt --> android_slave_app_src_main_java_com_oojoo_farm_slave_vision_Ojp1_kt
```

## 4. API map — backend routes the apps call

Endpoints of the Retrofit `ApiService` matched against the mounts in `backend/src/app.js` plus the paths in each route file. Arrow number = endpoint count.
(Paths called outside Retrofit — the Farmer's SSE command stream and model file downloads — are not included.)

```mermaid
flowchart LR
  master(["Master app"])
  farmer(["Farmer app"])
  subgraph routes["backend/src/routes"]
    r_analysis["analysis.js"]
    r_commands["commands.js"]
    r_community["community.js"]
    r_detectors["detectors.js"]
    r_diagnosis["diagnosis.js"]
    r_events["events.js"]
    r_harvests["harvests.js"]
    r_household["household.js"]
    r_live["live.js"]
    r_market["market.js"]
    r_messages["messages.js"]
    r_notifications["notifications.js"]
    r_pairing["pairing.js"]
    r_payments["payments.js"]
    r_photos["photos.js"]
    r_plants["plants.js"]
    r_policy["policy.js"]
    r_report["report.js"]
    r_subscription["subscription.js"]
    r_users["users.js"]
    r_videos["videos.js"]
    r_watering["watering.js"]
    r_weather["weather.js"]
  end
  r_app["app.js (/health)"]
  farmer -- 1 --> r_analysis
  farmer -- 1 --> r_app
  farmer -- 2 --> r_commands
  farmer -- 3 --> r_detectors
  farmer -- 1 --> r_diagnosis
  farmer -- 1 --> r_events
  farmer -- 1 --> r_live
  farmer -- 2 --> r_pairing
  farmer -- 2 --> r_photos
  farmer -- 2 --> r_plants
  farmer -- 1 --> r_policy
  farmer -- 2 --> r_videos
  farmer -- 1 --> r_watering
  farmer -- 2 --> r_weather
  master -- 2 --> r_analysis
  master -- 1 --> r_app
  master -- 2 --> r_commands
  master -- 8 --> r_community
  master -- 3 --> r_detectors
  master -- 2 --> r_diagnosis
  master -- 2 --> r_events
  master -- 3 --> r_harvests
  master -- 6 --> r_household
  master -- 8 --> r_market
  master -- 4 --> r_messages
  master -- 2 --> r_notifications
  master -- 7 --> r_pairing
  master -- 2 --> r_payments
  master -- 2 --> r_photos
  master -- 5 --> r_plants
  master -- 2 --> r_policy
  master -- 1 --> r_report
  master -- 3 --> r_subscription
  master -- 6 --> r_users
  master -- 3 --> r_videos
  master -- 2 --> r_watering
  master -- 2 --> r_weather
```

Every app call matches a backend route.

<details>
<summary>Full endpoint table</summary>

| App | Function | Call | Backend route file |
|---|---|---|---|
| master | `health` | `GET /health` | `app.js` |
| master | `createUser` | `POST /api/users` | `routes/users.js` |
| master | `registerUser` | `POST /api/users/register` | `routes/users.js` |
| master | `loginUser` | `POST /api/users/login` | `routes/users.js` |
| master | `user` | `GET /api/users/{id}` | `routes/users.js` |
| master | `pairCode` | `POST /api/pairing/code` | `routes/pairing.js` |
| master | `pairVerify` | `POST /api/pairing/verify` | `routes/pairing.js` |
| master | `slaves` | `GET /api/pairing/{userId}` | `routes/pairing.js` |
| master | `heartbeat` | `POST /api/pairing/heartbeat` | `routes/pairing.js` |
| master | `unpair` | `DELETE /api/pairing/{slaveId}` | `routes/pairing.js` |
| master | `renameSlave` | `PUT /api/pairing/{slaveId}` | `routes/pairing.js` |
| master | `setSlaveIcon` | `PUT /api/pairing/{slaveId}/icon` | `routes/pairing.js` |
| master | `policy` | `GET /api/policy/{slaveId}` | `routes/policy.js` |
| master | `setPolicy` | `PUT /api/policy/{slaveId}` | `routes/policy.js` |
| master | `plants` | `GET /api/plants/{userId}` | `routes/plants.js` |
| master | `createPlant` | `POST /api/plants` | `routes/plants.js` |
| master | `updatePlant` | `PUT /api/plants/plant/{id}` | `routes/plants.js` |
| master | `deletePlant` | `DELETE /api/plants/plant/{id}` | `routes/plants.js` |
| master | `plant` | `GET /api/plants/plant/{id}` | `routes/plants.js` |
| master | `reportEvent` | `POST /api/events` | `routes/events.js` |
| master | `events` | `GET /api/events/{slaveId}` | `routes/events.js` |
| master | `wateringLog` | `POST /api/watering/log` | `routes/watering.js` |
| master | `waterings` | `GET /api/watering/{plantId}` | `routes/watering.js` |
| master | `sendCommand` | `POST /api/commands` | `routes/commands.js` |
| master | `plantDetectors` | `GET /api/detectors/plant/{plantId}` | `routes/detectors.js` |
| master | `addDetector` | `POST /api/detectors/plant/{plantId}/{moduleId}` | `routes/detectors.js` |
| master | `removeDetector` | `DELETE /api/detectors/plant/{plantId}/{moduleId}` | `routes/detectors.js` |
| master | `commandHistory` | `GET /api/commands/history/{slaveId}` | `routes/commands.js` |
| master | `notifications` | `GET /api/notifications/{userId}` | `routes/notifications.js` |
| master | `marketCategories` | `GET /api/market/categories` | `routes/market.js` |
| master | `marketProducts` | `GET /api/market/products` | `routes/market.js` |
| master | `marketProduct` | `GET /api/market/products/{id}` | `routes/market.js` |
| master | `marketBundles` | `GET /api/market/bundles` | `routes/market.js` |
| master | `marketRecommendations` | `GET /api/market/recommendations/{userId}` | `routes/market.js` |
| master | `marketAffiliate` | `POST /api/market/affiliate/{id}` | `routes/market.js` |
| master | `marketCreateOrder` | `POST /api/market/orders` | `routes/market.js` |
| master | `marketOrders` | `GET /api/market/orders/{userId}` | `routes/market.js` |
| master | `communityPosts` | `GET /api/community/posts` | `routes/community.js` |
| master | `communityPost` | `GET /api/community/posts/{id}` | `routes/community.js` |
| master | `communityCreate` | `POST /api/community/posts` | `routes/community.js` |
| master | `communityAttachVideo` | `PATCH /api/community/posts/{id}/video` | `routes/community.js` |
| master | `communityComment` | `POST /api/community/posts/{id}/comments` | `routes/community.js` |
| master | `communityStatus` | `PATCH /api/community/posts/{id}/status` | `routes/community.js` |
| master | `communityReport` | `POST /api/community/report` | `routes/community.js` |
| master | `communityBlock` | `POST /api/community/block` | `routes/community.js` |
| master | `report` | `GET /api/report/{slaveId}` | `routes/report.js` |
| master | `subscriptionPlans` | `GET /api/subscription/plans` | `routes/subscription.js` |
| master | `subscription` | `GET /api/subscription/{userId}` | `routes/subscription.js` |
| master | `subscribe` | `POST /api/subscription` | `routes/subscription.js` |
| master | `weather` | `GET /api/weather/{region}` | `routes/weather.js` |
| master | `weatherByCoords` | `GET /api/weather/coords` | `routes/weather.js` |
| master | `videoByCommand` | `GET /api/videos/by-command/{commandId}` | `routes/videos.js` |
| master | `plantVideos` | `GET /api/videos/plant/{plantId}` | `routes/videos.js` |
| master | `plantPhotos` | `GET /api/photos/plant/{plantId}` | `routes/photos.js` |
| master | `userPhotos` | `GET /api/photos/user/{userId}` | `routes/photos.js` |
| master | `latestAnalysis` | `GET /api/analysis/latest/{plantId}` | `routes/analysis.js` |
| master | `analysisHistory` | `GET /api/analysis/history/{plantId}` | `routes/analysis.js` |
| master | `household` | `GET /api/household/{userId}` | `routes/household.js` |
| master | `householdInvite` | `POST /api/household/invite` | `routes/household.js` |
| master | `householdAccept` | `POST /api/household/accept` | `routes/household.js` |
| master | `householdLeave` | `POST /api/household/leave` | `routes/household.js` |
| master | `householdRemoveMember` | `DELETE /api/household/member/{memberId}` | `routes/household.js` |
| master | `householdRevokeInvite` | `DELETE /api/household/invite/{code}` | `routes/household.js` |
| master | `plantDiagnoses` | `GET /api/diagnosis/plant/{plantId}` | `routes/diagnosis.js` |
| master | `confirmDiagnosis` | `POST /api/diagnosis/sessions/{id}/confirm` | `routes/diagnosis.js` |
| master | `videoInfo` | `GET /api/videos/{videoId}` | `routes/videos.js` |
| master | `saveHarvest` | `POST /api/harvests` | `routes/harvests.js` |
| master | `harvestsByPlant` | `GET /api/harvests/plant/{plantId}` | `routes/harvests.js` |
| master | `harvestsByUser` | `GET /api/harvests/user/{userId}` | `routes/harvests.js` |
| master | `updateProfile` | `PUT /api/users/{id}` | `routes/users.js` |
| master | `conversations` | `GET /api/messages/conversations` | `routes/messages.js` |
| master | `messageThread` | `GET /api/messages/thread` | `routes/messages.js` |
| master | `sendMessage` | `POST /api/messages` | `routes/messages.js` |
| master | `markMessagesRead` | `POST /api/messages/read` | `routes/messages.js` |
| master | `markNotificationsRead` | `POST /api/notifications/{userId}/read` | `routes/notifications.js` |
| master | `registerPushToken` | `POST /api/users/{id}/push-token` | `routes/users.js` |
| master | `tossStart` | `POST /api/payments/toss/start` | `routes/payments.js` |
| master | `tossStatus` | `POST /api/payments/toss/status` | `routes/payments.js` |
| farmer | `health` | `GET /health` | `app.js` |
| farmer | `pairVerify` | `POST /api/pairing/verify` | `routes/pairing.js` |
| farmer | `heartbeat` | `POST /api/pairing/heartbeat` | `routes/pairing.js` |
| farmer | `wateringLog` | `POST /api/watering/log` | `routes/watering.js` |
| farmer | `reportEvent` | `POST /api/events` | `routes/events.js` |
| farmer | `slavePlants` | `GET /api/plants/slave/{slaveId}` | `routes/plants.js` |
| farmer | `updatePlant` | `PUT /api/plants/plant/{id}` | `routes/plants.js` |
| farmer | `pendingCommands` | `GET /api/commands/pending/{slaveId}` | `routes/commands.js` |
| farmer | `commandDone` | `POST /api/commands/{id}/done` | `routes/commands.js` |
| farmer | `policy` | `GET /api/policy/{slaveId}` | `routes/policy.js` |
| farmer | `reportAnalysis` | `POST /api/analysis/report` | `routes/analysis.js` |
| farmer | `weather` | `GET /api/weather/{region}` | `routes/weather.js` |
| farmer | `weatherByCoords` | `GET /api/weather/coords` | `routes/weather.js` |
| farmer | `uploadVideo` | `POST /api/videos/upload/{slaveId}` | `routes/videos.js` |
| farmer | `uploadPhoto` | `POST /api/photos/upload/{slaveId}` | `routes/photos.js` |
| farmer | `plantPhotos` | `GET /api/photos/plant/{plantId}` | `routes/photos.js` |
| farmer | `detectorCatalog` | `GET /api/detectors` | `routes/detectors.js` |
| farmer | `reportDetectorResults` | `POST /api/detectors/results` | `routes/detectors.js` |
| farmer | `reportDetectorState` | `POST /api/detectors/state` | `routes/detectors.js` |
| farmer | `clipFailed` | `POST /api/videos/clip-failed/{slaveId}` | `routes/videos.js` |
| farmer | `uploadLiveFrame` | `POST /api/live/{slaveId}/frame` | `routes/live.js` |
| farmer | `uploadDiagnosisSession` | `POST /api/diagnosis/sessions/{slaveId}` | `routes/diagnosis.js` |

</details>
