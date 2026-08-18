package top.feiyangdigital.utils.ruleCacheMap;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AddRuleCacheMap {

    private final Map<String, String> userToGroupMap = new ConcurrentHashMap<>();
    private final Map<String, String> userToGroupNameMap = new ConcurrentHashMap<>();
    private final Map<String, String> userToKeywordsFlagMap = new ConcurrentHashMap<>();
    private final Map<String,String> userToAiFlagMap = new ConcurrentHashMap<>();
    private final Map<String,String> userToCrontabFlagMap = new ConcurrentHashMap<>();

    public void updateUserMapping(String userId, String groupId, String groupName, String keywordsFlag,String aiFlag,String crontabFlag) {
        // ConcurrentHashMap does not allow null values. Remove unknown fields instead of throwing.
        putOrRemove(userToGroupMap, userId, groupId);
        putOrRemove(userToGroupNameMap, userId, groupName);
        putOrRemove(userToKeywordsFlagMap, userId, keywordsFlag);
        putOrRemove(userToAiFlagMap, userId, aiFlag);
        putOrRemove(userToCrontabFlagMap, userId, crontabFlag);
    }

    private void putOrRemove(Map<String, String> map, String key, String value) {
        if (key == null) {
            return;
        }
        if (value == null) {
            map.remove(key);
        } else {
            map.put(key, value);
        }
    }

    public String getGroupIdForUser(String userId) {
        return userToGroupMap.get(userId);
    }

    public String getGroupNameForUser(String userId) {
        return userToGroupNameMap.get(userId);
    }

    public String getKeywordsFlagForUser(String userId) {
        return userToKeywordsFlagMap.get(userId);
    }

    public String getAiFlagForUser(String userId) {
        return userToAiFlagMap.get(userId);
    }

    public String getCrontabFlagForUser(String userId){
        return userToCrontabFlagMap.get(userId);
    }

    public void clearMappingsForUser(String userId) {
        userToGroupMap.remove(userId);
        userToGroupNameMap.remove(userId);
        userToKeywordsFlagMap.remove(userId);
        userToAiFlagMap.remove(userId);
        userToCrontabFlagMap.remove(userId);
    }
}
