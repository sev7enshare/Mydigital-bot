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
import top.feiyangdigital.utils.MatchList;
import top.feiyangdigital.utils.TimerDelete;
import top.feiyangdigital.utils.groupCaptch.RestrictOrUnrestrictUser;

import java.util.List;

@Slf4j
@Component
public class AiCheckMessage {

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

    public void checkMessage(AbsSender sender, Update update) throws TelegramApiException {
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

    public void contentAiOption(AbsSender sender, String groupId, String userId, String firstName, Integer messageId, String content, String rawText) {
        BotRecord botRecord = botRecordService.selBotRecordByGidAndUid(groupId, userId);
        if (botRecord != null) {
            Integer violationCount = botRecord.getViolationcount();
            Integer normalCount = botRecord.getNormalcount();
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
            if (adLearningService.isKnownSpam(rawText)) {
                String text = String.format("用户 <b><a href=\"tg://user?id=%d\">%s</a></b> 发送内容命中本地广告缓存，已删除。", Long.valueOf(userId), firstName);
                String otherText = String.format("<b>违规用户UserID为：<a href=\"tg://user?id=%d\">%s</a></b>", Long.valueOf(userId), userId);
                SendMessage notification = new SendMessage();
                notification.setChatId(groupId);
                notification.setText(text + "\n" + otherText);
                notification.setParseMode(ParseMode.HTML);
                timerDelete.deleteMessageImmediatelyAndNotifyAfterDelay(sender, notification, groupId, messageId, Long.valueOf(userId), 90);
                adLearningService.recordKnownSpamHit(rawText, groupId, userId);
                BotRecord botRecord1 = new BotRecord();
                botRecord1.setViolationcount(violationCount + 1);
                botRecord1.setLastmessage(content);
                botRecordService.updateRecordByGidAndUid(groupId, userId, botRecord1);
                return;
            } else if (normalCount >= 5) {
                return;
            }
            List<ChatChoice> list = openAiApiService.getOpenAiAnalyzeResult(content);
            if (!list.isEmpty()) {
                if (list.get(0).getMessage() == null || !StringUtils.hasText(list.get(0).getMessage().getContent())) {
                    log.warn("AI检测返回内容为空，跳过本次检测。");
                    return;
                }
                JSONObject jsonObject;
                try {
                    jsonObject = JSONObject.parseObject(list.get(0).getMessage().getContent());
                } catch (Exception e) {
                    log.warn("AI检测返回内容不是合法JSON，跳过本次检测。返回内容：{}", list.get(0).getMessage().getContent(), e);
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
                    String text = String.format("用户 <b><a href=\"tg://user?id=%d\">%s</a></b> 已被AI检测发送违规词，判断原因如下：\n<tg-spoiler>%s</tg-spoiler>", Long.valueOf(userId), firstName, spamReason);
                    String otherText = String.format("<b>违规用户UserID为：<a href=\"tg://user?id=%d\">%s</a></b>", Long.valueOf(userId), userId);
                    SendMessage notification = new SendMessage();
                    notification.setChatId(groupId);
                    notification.setText(text + "\n" + otherText);
                    notification.setParseMode(ParseMode.HTML);
                    timerDelete.deleteMessageImmediatelyAndNotifyAfterDelay(sender, notification, groupId, messageId, Long.valueOf(userId), 90);
                    adLearningService.recordAiSpam(groupId, userId, rawText, spamChance, spamReason);
                    botRecord1.setViolationcount(violationCount + 1);
                } else {
                    botRecord1.setNormalcount(normalCount + 1);
                }
                botRecord1.setLastmessage(content);
                botRecordService.updateRecordByGidAndUid(groupId, userId, botRecord1);
            }
        }
    }

}
