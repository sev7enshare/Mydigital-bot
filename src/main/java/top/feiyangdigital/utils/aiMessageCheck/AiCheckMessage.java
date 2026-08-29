package top.feiyangdigital.utils.aiMessageCheck;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSONObject;
import com.unfbx.chatgpt.entity.chat.ChatChoice;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.bots.AbsSender;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import top.feiyangdigital.entity.BotRecord;
import top.feiyangdigital.entity.GroupInfoWithBLOBs;
import top.feiyangdigital.entity.KeywordsFormat;
import top.feiyangdigital.handleService.MessageHandle;
import top.feiyangdigital.handleService.OpenAiApiService;
import top.feiyangdigital.sqlService.AdLearningService;
import top.feiyangdigital.sqlService.BotRecordService;
import top.feiyangdigital.sqlService.GroupInfoService;
import top.feiyangdigital.utils.CheckUser;
import top.feiyangdigital.utils.MatchList;
import top.feiyangdigital.utils.TimerDelete;
import top.feiyangdigital.utils.groupCaptch.RestrictOrUnrestrictUser;

import java.text.Normalizer;
import java.util.Locale;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class AiCheckMessage {

    private static final Pattern JSON_OBJECT_PATTERN = Pattern.compile("\\{[\\s\\S]*}");
    private static final Pattern SPAM_CHANCE_PATTERN = Pattern.compile("(?i)\"?spamChance\"?\\s*:\\s*\"?(\\d{1,2})");
    private static final Pattern CONTACT_OR_LINK_PATTERN = Pattern.compile("(?i)(https?://|t\\.me/|telegram:|line\\.me/|@\\w{5,}|startapp=)");
    private static final Pattern HIGH_RISK_AD_PATTERN = Pattern.compile("(?i)(\\bgv\\b|google\\s*voice|linkedin|2fa|\\bck\\b|接码|虚拟卡|信用卡|裸聊|美女|hot|v1d|色情|成人|赌博|博彩|洗钱|代付)");
    private static final Pattern SALES_INTENT_PATTERN = Pattern.compile("(?i)(出售|售卖|供出|出号|接单|商家|收款码|商务|business|广告|成本低|引流|私聊|加我|联系|contact|telegram|line)");
    private static final Pattern BENIGN_SERVICE_PATTERN = Pattern.compile("(?i)(签到|checkin|随机流量|每日流量|获取\\s*\\d+\\s*-\\s*\\d+\\s*(mb|mib|gb|gib)|绑定本站账号|未绑定本站账号|套餐入口|节点|订阅|客户端|线路|域名被墙|换\\s*ip|自动更换\\s*ip)");

    @Autowired
    private TimerDelete timerDelete;

    @Autowired
    private GroupInfoService groupInfoService;

    @Autowired
    private MatchList matchList;

    @Autowired
    private MessageHandle messageHandle;

    @Autowired
    private BotRecordService botRecordService;

    @Autowired
    private RestrictOrUnrestrictUser restrictOrUnrestrictUser;

    @Autowired
    private OpenAiApiService openAiApiService;

    @Autowired
    private AdLearningService adLearningService;

    @Autowired
    private CheckUser checkUser;

    public void checkMessage(AbsSender sender, Update update) throws TelegramApiException {
        if (checkUser.isGroupAdmin(sender, update)) {
            return;
        }
        String groupId = update.getMessage().getChatId().toString();
        String userId = update.getMessage().getFrom().getId().toString();
        Integer messageId = update.getMessage().getMessageId();
        String firstName = update.getMessage().getFrom().getFirstName();
        String userName = StrUtil.concat(true, firstName, update.getMessage().getFrom().getLastName());
        String rawText = update.getMessage().getText();
        String content = userName + "," + rawText;
        List<KeywordsFormat> keywordsFormatList = matchList.createBanKeyDeleteOptionList(update);
        if (keywordsFormatList != null) {
            if (messageHandle.processUserMessage(sender, update, keywordsFormatList)) {
                return;
            }
        }
        GroupInfoWithBLOBs groupInfoWithBLOBs = groupInfoService.selAllByGroupId(groupId);
        if (groupInfoWithBLOBs != null && "open".equals(groupInfoWithBLOBs.getAiflag()) && StringUtils.hasText(content)) {
            contentAiOption(sender, groupId, userId, firstName, messageId, content, rawText);
        }
    }

    public void contentAiOption(AbsSender sender, String groupId, String userId, String firstName, Integer messageId, String content) {
        contentAiOption(sender, groupId, userId, firstName, messageId, content, content);
    }

    public void contentAiOption(AbsSender sender, String groupId, String userId, String firstName, Integer messageId, String content, String rawText) {
        BotRecord botRecord = botRecordService.selBotRecordByGidAndUid(groupId, userId);
        if (botRecord == null) {
            botRecordService.addUserRecord(groupId, userId, String.valueOf(System.currentTimeMillis()));
            botRecord = new BotRecord();
        }
        Integer violationCount = botRecord.getViolationcount() == null ? 0 : botRecord.getViolationcount();
        Integer normalCount = botRecord.getNormalcount() == null ? 0 : botRecord.getNormalcount();
        if (violationCount >= 5) {
            String text = String.format("用户 <b><a href=\"tg://user?id=%d\">%s</a></b> 已被AI检测违规超过5次，永久限制发言！", Long.valueOf(userId), firstName);
            String otherText = String.format("<b>违规用户UserID为：<a href=\"tg://user?id=%d\">%s</a></b>", Long.valueOf(userId), userId);
            SendMessage notification = new SendMessage();
            notification.setChatId(groupId);
            notification.setText(text + "\n" + otherText);
            notification.setParseMode(ParseMode.HTML);
            timerDelete.deleteMessageImmediatelyAndNotifyAfterDelay(sender, notification, groupId, messageId, Long.valueOf(userId), 90);
            restrictOrUnrestrictUser.restrictUser(sender, Long.valueOf(userId), groupId, 0L);
            return;
        }

        if (isObviousLinkAd(content)) {
            handleSpam(sender, groupId, userId, firstName, messageId, content, rawText, violationCount,
                    10, "命中本地硬规则：疑似引流/联系方式/账号贩卖广告", "local_rule");
            return;
        }

        if (adLearningService.isKnownSpam(rawText)) {
            handleSpam(sender, groupId, userId, firstName, messageId, content, rawText, violationCount,
                    10, "命中本地广告缓存", "cache");
            adLearningService.recordKnownSpamHit(rawText, groupId, userId);
            return;
        }

        if (normalCount >= 5) {
            return;
        }
        List<ChatChoice> list = openAiApiService.getOpenAiAnalyzeResult(content);
        if (!list.isEmpty()) {
            if (list.get(0).getMessage() == null || !StringUtils.hasText(list.get(0).getMessage().getContent())) {
                log.warn("AI检测返回内容为空，跳过本次检测。");
                return;
            }
            JSONObject jsonObject;
            String aiContent = list.get(0).getMessage().getContent();
            jsonObject = parseAiResult(aiContent);
            if (jsonObject == null) {
                log.warn("AI检测返回内容不是合法JSON，跳过本次检测。返回内容：{}", aiContent);
                return;
            }
            Integer spamChance = jsonObject.getInteger("spamChance");
            String spamReason = jsonObject.getString("spamReason");
            if (spamChance == null) {
                log.warn("AI检测返回JSON缺少spamChance，跳过本次检测。返回内容：{}", jsonObject);
                return;
            }
            BotRecord botRecord1 = new BotRecord();
            if (spamChance >= 6) {
                handleSpam(sender, groupId, userId, firstName, messageId, content, rawText, violationCount,
                        spamChance, spamReason, "deepseek");
            } else {
                botRecord1.setNormalcount(normalCount + 1);
                botRecord1.setLastmessage(content);
                botRecordService.updateRecordByGidAndUid(groupId, userId, botRecord1);
            }
        }
    }

    private void handleSpam(AbsSender sender, String groupId, String userId, String firstName, Integer messageId,
                            String content, String rawText, Integer violationCount, Integer spamChance,
                            String spamReason, String source) {
        String text = String.format("用户 <b><a href=\"tg://user?id=%d\">%s</a></b> 已被检测发送违规广告，判断原因如下：\n<tg-spoiler>%s</tg-spoiler>", Long.valueOf(userId), escapeHtml(firstName), safeReason(spamReason));
        String otherText = String.format("<b>违规用户UserID为：<a href=\"tg://user?id=%d\">%s</a></b>", Long.valueOf(userId), userId);
        SendMessage notification = new SendMessage();
        notification.setChatId(groupId);
        notification.setText(text + "\n" + otherText);
        notification.setParseMode(ParseMode.HTML);
        timerDelete.deleteMessageImmediatelyAndNotifyAfterDelay(sender, notification, groupId, messageId, Long.valueOf(userId), 90);
        adLearningService.recordAiSpam(groupId, userId, rawText, spamChance, safeReason(spamReason) + " [" + source + "]");
        BotRecord botRecord1 = new BotRecord();
        botRecord1.setViolationcount(violationCount + 1);
        botRecord1.setLastmessage(content);
        botRecordService.updateRecordByGidAndUid(groupId, userId, botRecord1);
    }

    private JSONObject parseAiResult(String content) {
        if (!StringUtils.hasText(content)) {
            return null;
        }
        try {
            return JSONObject.parseObject(content);
        } catch (Exception ignored) {
            Matcher jsonMatcher = JSON_OBJECT_PATTERN.matcher(content);
            if (jsonMatcher.find()) {
                try {
                    return JSONObject.parseObject(jsonMatcher.group());
                } catch (Exception ignoredAgain) {
                    // Fall through to regex extraction for truncated JSON.
                }
            }
            Matcher chanceMatcher = SPAM_CHANCE_PATTERN.matcher(content);
            if (chanceMatcher.find()) {
                JSONObject fallback = new JSONObject();
                fallback.put("spamChance", Integer.valueOf(chanceMatcher.group(1)));
                fallback.put("spamReason", "AI返回JSON不完整，但已提取到违规评分");
                return fallback;
            }
        }
        return null;
    }

    private boolean isObviousLinkAd(String content) {
        if (!StringUtils.hasText(content)) {
            return false;
        }
        String normalized = Normalizer.normalize(content, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        if (!CONTACT_OR_LINK_PATTERN.matcher(normalized).find()) {
            return false;
        }
        boolean hasHighRiskTerm = HIGH_RISK_AD_PATTERN.matcher(normalized).find();
        boolean hasSalesIntent = SALES_INTENT_PATTERN.matcher(normalized).find();
        boolean isBenignServiceMessage = BENIGN_SERVICE_PATTERN.matcher(normalized).find();
        if (isBenignServiceMessage && !hasHighRiskTerm && !hasSalesIntent) {
            return false;
        }
        return hasHighRiskTerm || hasSalesIntent;
    }

    private String safeReason(String reason) {
        if (!StringUtils.hasText(reason)) {
            return "疑似广告或引流内容";
        }
        String value = reason.length() > 180 ? reason.substring(0, 180) : reason;
        return escapeHtml(value);
    }

    private String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

}
