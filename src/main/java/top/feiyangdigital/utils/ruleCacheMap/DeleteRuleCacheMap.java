package top.feiyangdigital.utils.ruleCacheMap;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class DeleteRuleCacheMap {

    private final Map<String, String> userToGroupMap = new ConcurrentHashMap<>();
    private final Map<String, String> userToGroupNameMap = new ConcurrentHashMap<>();
    private final Map<String, String> userToDeleteKeywordFlagMap = new ConcurrentHashMap<>();

    public void updateUserMapping(String userId, String groupId, String groupName, String deleteKeywordFlag) {
        // ConcurrentHashMap does not allow null values. Remove unknown fields instead of throwing.
        putOrRemove(userToGroupMap, userId, groupId);
        putOrRemove(userToGroupNameMap, userId, groupName);
        putOrRemove(userToDeleteKeywordFlagMap, userId, deleteKeywordFlag);
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

    public String getDeleteKeywordFlagMap(String userId) {
        return userToDeleteKeywordFlagMap.get(userId);
    }

    public void clearMappingsForUser(String userId) {
        userToGroupMap.remove(userId);
        userToGroupNameMap.remove(userId);
        userToDeleteKeywordFlagMap.remove(userId);
    }
}
