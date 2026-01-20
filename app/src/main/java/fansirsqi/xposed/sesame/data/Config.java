package fansirsqi.xposed.sesame.data;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import fansirsqi.xposed.sesame.entity.UserEntity;
import fansirsqi.xposed.sesame.model.ModelConfig;
import fansirsqi.xposed.sesame.model.ModelField;
import fansirsqi.xposed.sesame.model.ModelFields;
import fansirsqi.xposed.sesame.task.ModelTask;
import fansirsqi.xposed.sesame.task.TaskCommon;
import fansirsqi.xposed.sesame.util.Files;
import fansirsqi.xposed.sesame.util.JsonUtil;
import fansirsqi.xposed.sesame.util.Log;
import fansirsqi.xposed.sesame.util.maps.UserMap;
import fansirsqi.xposed.sesame.util.StringUtil;
import lombok.Data;

/**
 * 配置类，负责加载、保存、管理应用的配置数据。
 * 已重构为支持多实例，以解决多用户环境下的配置覆盖和错乱问题。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class Config {
    private static final String TAG = Config.class.getSimpleName();

    // 内存缓存，key 是 userId
    private static final Map<String, Config> userConfigs = new ConcurrentHashMap<>();

    // 是否初始化标志
    @JsonIgnore
    private volatile boolean init = false;

    // 存储模型字段的映射
    private final Map<String, ModelFields> modelFieldsMap = new ConcurrentHashMap<>();

    /**
     * 获取指定用户的配置实例
     */
    public static Config getInstance(String userId) {
        if (StringUtil.isEmpty(userId)) {
            userId = "default";
        }
        return userConfigs.computeIfAbsent(userId, id -> new Config());
    }

    /**
     * 获取当前全局“已激活”的配置实例（兼容旧逻辑，通常指当前登录用户）
     */
    public static Config getActiveConfig() {
        String currentUid = UserMap.INSTANCE.getCurrentUid();
        return getInstance(currentUid);
    }

    /**
     * ！！！兼容性字段！！！
     * 为了解决编译错误，暂时将 INSTANCE 指向当前活跃用户的配置。
     * 建议后续逐步替换为 getInstance(userId) 或 getActiveConfig()。
     */
    @Deprecated
    public static Config getINSTANCE() {
        return getActiveConfig();
    }

    /**
     * 设置新的模型字段配置
     */
    public void setModelFieldsMap(Map<String, ModelFields> newModels) {
        modelFieldsMap.clear();
        Map<String, ModelConfig> modelConfigMap = ModelTask.getModelConfigMap();
        if (newModels == null) {
            newModels = new HashMap<>();
        }
        for (ModelConfig modelConfig : modelConfigMap.values()) {
            String modelCode = modelConfig.getCode();
            ModelFields newModelFields = new ModelFields();
            ModelFields configModelFields = modelConfig.getFields();
            ModelFields modelFields = newModels.get(modelCode);
            if (modelFields != null) {
                for (ModelField<?> configModelField : configModelFields.values()) {
                    ModelField<?> modelField = modelFields.get(configModelField.getCode());
                    try {
                        if (modelField != null) {
                            Object value = modelField.getValue();
                            if (value != null) {
                                configModelField.setObjectValue(value);
                            }
                        }
                    } catch (Exception e) {
                        Log.printStackTrace(e);
                    }
                    newModelFields.addField(configModelField);
                }
            } else {
                for (ModelField<?> configModelField : configModelFields.values()) {
                    newModelFields.addField(configModelField);
                }
            }
            modelFieldsMap.put(modelCode, newModelFields);
        }
    }

    public Boolean hasModelFields(String modelCode) {
        return modelFieldsMap.containsKey(modelCode);
    }

    public Boolean hasModelField(String modelCode, String fieldCode) {
        ModelFields modelFields = modelFieldsMap.get(modelCode);
        if (modelFields == null) {
            return false;
        }
        return modelFields.containsKey(fieldCode);
    }

    /**
     * 判断配置文件是否已修改
     */
    public static Boolean isModify(String userId) {
        String json = null;
        File configV2File;
        if (StringUtil.isEmpty(userId)) {
            configV2File = Files.getDefaultConfigV2File();
        } else {
            configV2File = Files.getConfigV2File(userId);
        }
        if (configV2File.exists()) {
            json = Files.readFromFile(configV2File);
        }
        if (json != null) {
            String formatted = JsonUtil.formatJson(getInstance(userId));
            return formatted == null || !formatted.equals(json);
        }
        return true;
    }

    /**
     * 保存配置文件
     */
    public static synchronized Boolean save(String userId, Boolean force) {
        if (!force && !isModify(userId)) {
            return true;
        }
        Config config = getInstance(userId);
        String json;
        try {
            json = JsonUtil.formatJson(config);
            if (json == null) {
                throw new IllegalStateException("配置格式化失败，返回的 JSON 为空");
            }
        } catch (Exception e) {
            Log.printStackTrace(TAG, e);
            Log.record(TAG, "保存用户配置失败，格式化 JSON 时出错");
            return false;
        }
        boolean success;
        try {
            if (StringUtil.isEmpty(userId)) {
                success = Files.setDefaultConfigV2File(json);
            } else {
                success = Files.setConfigV2File(userId, json);
            }
            if (!success) {
                throw new IOException("配置文件保存失败");
            }
            String userName;
            if (StringUtil.isEmpty(userId)) {
                userName = "默认用户";
            } else {
                UserEntity userEntity = UserMap.get(userId);
                userName = userEntity != null ? userEntity.getShowName() : userId;
            }
            Log.record(TAG, "保存 [" + userName + "] 配置");
        } catch (Exception e) {
            Log.printStackTrace(TAG, e);
            Log.record(TAG, "保存用户配置失败");
            return false;
        }
        return true;
    }

    /**
     * 兼容旧版不带参数的判断
     */
    public static boolean isLoaded() {
        return getActiveConfig().init;
    }

    public static boolean isLoaded(String userId) {
        return getInstance(userId).init;
    }

    /**
     * 加载配置文件
     */
    public static synchronized Config load(String userId) {
        Config config = getInstance(userId);
        Log.record(TAG, "开始加载配置: " + userId);
        String userName = "";
        File configV2File = null;
        try {
            if (StringUtil.isEmpty(userId)) {
                configV2File = Files.getDefaultConfigV2File();
                userName = "默认";
                if (!configV2File.exists()) {
                    Log.record(TAG, "默认配置文件不存在，初始化新配置");
                    config.unloadInternal();
                    Files.write2File(JsonUtil.formatJson(config), configV2File);
                }
            } else {
                configV2File = Files.getConfigV2File(userId);
                UserEntity userEntity = UserMap.get(userId);
                userName = (userEntity == null) ? userId : userEntity.getShowName();
            }

            Log.record(TAG, "加载配置: " + userName);
            boolean configV2FileExists = configV2File.exists();
            boolean defaultConfigV2FileExists = Files.getDefaultConfigV2File().exists();

            if (configV2FileExists) {
                String json = Files.readFromFile(configV2File);
                try {
                    JsonUtil.copyMapper().readerForUpdating(config).readValue(json);
                    config.setModelFieldsMap(config.getModelFieldsMap());
                } catch (UnrecognizedPropertyException e) {
                    Log.error(TAG, "配置文件中存在无法识别的字段: '" + e.getPropertyName() + "'，将尝试移除并重新加载。");
                    try {
                        ObjectMapper mapper = JsonUtil.copyMapper();
                        JsonNode rootNode = mapper.readTree(json);
                        ((ObjectNode) rootNode).remove(e.getPropertyName());
                        String cleanedJson = mapper.writeValueAsString(rootNode);
                        mapper.readerForUpdating(config).readValue(cleanedJson);
                        config.setModelFieldsMap(config.getModelFieldsMap());
                        Log.error(TAG, "成功移除问题字段并加载配置。");
                        Files.write2File(JsonUtil.formatJson(config), configV2File);
                    } catch (Exception innerEx) {
                        Log.printStackTrace(TAG, "移除问题字段后，加载配置仍然失败。", innerEx);
                        throw innerEx;
                    }
                }
                String formatted = JsonUtil.formatJson(config);
                if (formatted != null && !formatted.equals(json)) {
                    Files.write2File(formatted, configV2File);
                }
            } else if (defaultConfigV2FileExists) {
                String json = Files.readFromFile(Files.getDefaultConfigV2File());
                JsonUtil.copyMapper().readerForUpdating(config).readValue(json);
                config.setModelFieldsMap(config.getModelFieldsMap());
                Log.record(TAG, "复制新配置: " + userName);
                Files.write2File(json, configV2File);
            } else {
                config.unloadInternal();
                Files.write2File(JsonUtil.formatJson(config), configV2File);
            }
        } catch (Throwable t) {
            Log.printStackTrace(TAG, "重置配置失败", t);
            try {
                config.unloadInternal();
                if (configV2File != null) {
                    Files.write2File(JsonUtil.formatJson(config), configV2File);
                }
            } catch (Exception e) {
                Log.printStackTrace(TAG, "重置配置失败", e);
            }
        }
        config.setInit(true);
        // 如果加载的是当前活跃用户的配置，则触发更新
        if (userId.equals(UserMap.INSTANCE.getCurrentUid())) {
            TaskCommon.update();
        }
        return config;
    }

    /**
     * 卸载指定配置
     */
    public static synchronized void unload(String userId) {
        getInstance(userId).unloadInternal();
    }

    /**
     * 兼容旧版不带参数的卸载
     */
    public static synchronized void unload() {
        getActiveConfig().unloadInternal();
    }

    private void unloadInternal() {
        for (ModelFields modelFields : this.modelFieldsMap.values()) {
            for (ModelField<?> modelField : modelFields.values()) {
                if (modelField != null) {
                    modelField.reset();
                }
            }
        }
    }

}