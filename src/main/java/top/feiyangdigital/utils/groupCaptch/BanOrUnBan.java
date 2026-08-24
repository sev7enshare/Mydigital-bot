package top.feiyangdigital.utils.groupCaptch;

import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.telegram.telegrambots.meta.api.methods.groupadministration.BanChatMember;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember;
import org.telegram.telegrambots.meta.api.methods.groupadministration.UnbanChatMember;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.MessageEntity;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember;
import org.telegram.telegrambots.meta.bots.AbsSender;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import top.feiyangdigital.entity.BaseInfo;
import top.feiyangdigital.entity.KeywordsFormat;
import top.feiyangdigital.sqlService.AdLearningService;
import top.feiyangdigital.utils.CheckUser;
import top.feiyangdigital.utils.SendContent;
import top.feiyangdigital.utils.TimerDelete;
import top.feiyangdigital.utils.userNameToUserId.ObtainUserId;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

@Component
@Slf4j
public class BanOrUnBan {

    @Autowired
    private TimerDelete timerDelete;

    @Autowired
    private CheckUser checkUser;

    @Autowired
    private SendContent sendContent;

    @Autowired
    private ObtainUserId obtainUserId;

    @Autowired
    private CaptchaManagerCacheMap captchaManagerCacheMap;

    @Autowired
    private AdLearningService adLearningService;

    private void unBanFunc(AbsSender sender, Update update, Long userId, String firstName, String chatId) {
        if (unBanUserById(sender, userId, chatId)) {
            KeywordsFormat keywordsFormat = new KeywordsFormat();
            String text1 = String.format("用户 <b><a href=\"tg://user?id=%d\">%s</a></b> 已被管理员解封。", userId, firstName);
            keywordsFormat.setReplyText(text1);
            timerDelete.sendTimedMessage(sender, sendContent.createResponseMessage(update, keywordsFormat, "html"), 60);
        }
    }

    private void commonFunc(AbsSender sender, Update update, Long userId, String firstName, String chatId, String text, String biaoshi) {
        String banReason = "";
        Long secondsToAdd = null;
        if (text.split(" ").length >= 3 && "noreply".equals(biaoshi)) {
            try {
                secondsToAdd = Long.valueOf(text.split(" ")[2]);
                if (text.split(" ").length >= 4) {
                    banReason = text.split(" ")[3];
                }
            } catch (NumberFormatException e) {
                banReason = text.split(" ")[2];
            }
        } else if (text.split(" ").length >= 2 && "reply".equals(biaoshi)) {
            try {
                secondsToAdd = Long.valueOf(text.split(" ")[1]);
                if (text.split(" ").length >= 3) {
                    banReason = text.split(" ")[2];
                }
            } catch (NumberFormatException e) {
                banReason = text.split(" ")[1];
            }
        }
        if (secondsToAdd == null || secondsToAdd <= 30L) {
            secondsToAdd = 0L;
        }
        if (banUserById(sender, userId, chatId, secondsToAdd)) {
            KeywordsFormat keywordsFormat = new KeywordsFormat();
            String text1 = String.format("用户 <b><a href=\"tg://user?id=%d\">%s</a></b> 已被管理员封禁。", userId, firstName);
            if (StringUtils.hasText(banReason)) {
                text1 += "\n" + String.format("封禁原因：<b>%s</b>！", banReason);
            }
            if (secondsToAdd != 0L) {
                text1 += "\n" + String.format("封禁时间：<b>%s秒</b>！", secondsToAdd);
            } else {
                text1 += "\n" + "封禁时间：<b>永久</b>！";
            }
            List<String> keywordsButtons = new ArrayList<>();
            keywordsButtons.add("👥管理员解封##adminUnBan" + userId);
            keywordsFormat.setKeywordsButtons(keywordsButtons);
            keywordsFormat.setReplyText(text1);
            String messageId = timerDelete.sendTimedMessage(sender, sendContent.createResponseMessage(update, keywordsFormat, "html"), 60);
            captchaManagerCacheMap.updateUserMapping(userId.toString(), chatId, 0, Integer.valueOf(messageId));
        }
    }

    public boolean banOption(AbsSender sender, Update update) throws TelegramApiException {
        String text = update.getMessage().getText().trim();
        String chatId = update.getMessage().getChatId().toString();
        Integer oldMessageId = update.getMessage().getMessageId();

        if (checkUser.isGroupAdmin(sender, update)) {
            if (text.startsWith("!ban") || text.startsWith("!ban@" + BaseInfo.getBotName())
                    || text.startsWith("/ban") || text.startsWith("/ban@" + BaseInfo.getBotName())
            ) {
                if (update.getMessage().hasEntities() && !"bot_command".equals(update.getMessage().getEntities().get(update.getMessage().getEntities().size() - 1).getType())) {
                    MessageEntity messageEntity = update.getMessage().getEntities().get(update.getMessage().getEntities().size() - 1);
                    if (text.split(" ").length >= 2 && text.split(" ")[1].contains("@") && "mention".equals(messageEntity.getType())) {
                        JSONObject jsonObject = obtainUserId.fetchUserWithOkHttp(messageEntity.getText());
                        Long userNameToId = jsonObject.getLong("id");
                        String userNameToFirstName = jsonObject.getString("first_name");
                        commonFunc(sender, update, userNameToId, userNameToFirstName, chatId, text, "noreply");
                    } else if (text.split(" ").length >= 2 && "text_mention".equals(messageEntity.getType())) {
                        Long userId = messageEntity.getUser().getId();
                        String firstName = messageEntity.getUser().getFirstName();
                        commonFunc(sender, update, userId, firstName, chatId, text, "noreply");
                    }
                } else if (text.split(" ").length >= 2 && update.getMessage().getReplyToMessage() == null) {
                    Long userId = Long.valueOf(text.split(" ")[1]);
                    GetChatMember getChatMember = new GetChatMember();
                    getChatMember.setUserId(userId);
                    getChatMember.setChatId(chatId);
                    ChatMember chatMember = sender.execute(getChatMember);
                    String firstName = chatMember.getUser().getFirstName();
                    commonFunc(sender, update, userId, firstName, chatId, text, "noreply");
                } else if (update.getMessage().getReplyToMessage() != null) {
                    Long userId = update.getMessage().getReplyToMessage().getFrom().getId();
                    String firstName = update.getMessage().getReplyToMessage().getFrom().getFirstName();
                    Integer banMessageId = update.getMessage().getReplyToMessage().getMessageId();
                    commonFunc(sender, update, userId, firstName, chatId, text, "reply");
                    learnFromRepliedMessage(update, chatId, userId.toString(), "admin_ban");
                    deleteMessageQuietly(sender, chatId, banMessageId, "删除 /ban 回复的广告消息失败");
                }
                deleteMessageQuietly(sender, chatId, oldMessageId, "删除 /ban 命令消息失败");
                return true;
            }
        } else if (text.startsWith("!ban") || text.startsWith("!ban@" + BaseInfo.getBotName())
                || text.startsWith("/ban") || text.startsWith("/ban@" + BaseInfo.getBotName())
        ) {
            deleteMessageQuietly(sender, chatId, oldMessageId, "删除非管理员 /ban 命令消息失败");
            return true;
        }
        return false;
    }

    public boolean dbanOption(AbsSender sender, Update update) throws TelegramApiException {
        String text = update.getMessage().getText().trim();
        String chatId = update.getMessage().getChatId().toString();
        Integer oldMessageId = update.getMessage().getMessageId();

        if (checkUser.isGroupAdmin(sender, update)) {
            if (text.startsWith("!dban") || text.startsWith("!dban@" + BaseInfo.getBotName())
                    || text.startsWith("/dban") || text.startsWith("/dban@" + BaseInfo.getBotName())
            ) {
                if (update.getMessage().getReplyToMessage() != null) {
                    Long userId = update.getMessage().getReplyToMessage().getFrom().getId();
                    String firstName = update.getMessage().getReplyToMessage().getFrom().getFirstName();
                    Integer dbanMessageId = update.getMessage().getReplyToMessage().getMessageId();
                    learnFromRepliedMessage(update, chatId, userId.toString(), "admin_dban");
                    deleteMessageQuietly(sender, chatId, dbanMessageId, "删除 /dban 回复的广告消息失败");
                    commonFunc(sender, update, userId, firstName, chatId, text, "reply");
                } else {
                    timerDelete.sendTimedMessage(sender, sendContent.messageText(update, "你必须回复一条消息，才能执行此操作！"), 10);
                }
                deleteMessageQuietly(sender, chatId, oldMessageId, "删除 /dban 命令消息失败");
                return true;
            }
        } else if (text.startsWith("!dban") || text.startsWith("!dban@" + BaseInfo.getBotName())
                || text.startsWith("/dban") || text.startsWith("/dban@" + BaseInfo.getBotName())
        ) {
            deleteMessageQuietly(sender, chatId, oldMessageId, "删除非管理员 /dban 命令消息失败");
            return true;
        }
        return false;

    }

    public boolean unBanOption(AbsSender sender, Update update) throws TelegramApiException {
        String text = update.getMessage().getText().trim();
        String chatId = update.getMessage().getChatId().toString();
        Integer oldMessageId = update.getMessage().getMessageId();

        if (checkUser.isGroupAdmin(sender, update)) {
            if (text.startsWith("!unban") || text.startsWith("!unban@" + BaseInfo.getBotName())
                    || text.startsWith("/unban") || text.startsWith("/unban@" + BaseInfo.getBotName())
            ) {
                if (update.getMessage().hasEntities() && !"bot_command".equals(update.getMessage().getEntities().get(update.getMessage().getEntities().size() - 1).getType())) {
                    MessageEntity messageEntity = update.getMessage().getEntities().get(update.getMessage().getEntities().size() - 1);
                    if (text.split(" ").length >= 2 && text.split(" ")[1].contains("@") && "mention".equals(messageEntity.getType())) {
                        JSONObject jsonObject = obtainUserId.fetchUserWithOkHttp(messageEntity.getText());
                        Long userNameToId = jsonObject.getLong("id");
                        String userNameToFirstName = jsonObject.getString("first_name");
                        unBanFunc(sender, update, userNameToId, userNameToFirstName, chatId);
                    } else if (text.split(" ").length >= 2 && "text_mention".equals(messageEntity.getType())) {
                        Long userId = messageEntity.getUser().getId();
                        String firstName = messageEntity.getUser().getFirstName();
                        unBanFunc(sender, update, userId, firstName, chatId);
                    }
                } else if (text.split(" ").length >= 2 && update.getMessage().getReplyToMessage() == null) {
                    Long userId = Long.valueOf(text.split(" ")[1]);
                    GetChatMember getChatMember = new GetChatMember();
                    getChatMember.setUserId(userId);
                    getChatMember.setChatId(chatId);
                    ChatMember chatMember = sender.execute(getChatMember);
                    String firstName = chatMember.getUser().getFirstName();
                    unBanFunc(sender, update, userId, firstName, chatId);
                } else if (update.getMessage().getReplyToMessage() != null) {
                    Long userId = update.getMessage().getReplyToMessage().getFrom().getId();
                    String firstName = update.getMessage().getReplyToMessage().getFrom().getFirstName();
                    unBanFunc(sender, update, userId, firstName, chatId);
                }
                deleteMessageQuietly(sender, chatId, oldMessageId, "删除 /unban 命令消息失败");
                return true;
            }
        } else if (text.startsWith("!unban") || text.startsWith("!unban@" + BaseInfo.getBotName())
                || text.startsWith("/unban") || text.startsWith("/unban@" + BaseInfo.getBotName())
        ) {
            deleteMessageQuietly(sender, chatId, oldMessageId, "删除非管理员 /unban 命令消息失败");
            return true;
        }
        return false;
    }


    public boolean banUserById(AbsSender sender, Long userId, String chatId, Long secondsToAdd) {
        BanChatMember banChatMember = new BanChatMember();
        banChatMember.setChatId(chatId);
        banChatMember.setUserId(userId);
        banChatMember.setUntilDateDateTime(ZonedDateTime.now().plusSeconds(secondsToAdd));
        try {
            sender.execute(banChatMember);
            return true;
        } catch (TelegramApiException e) {
            log.error("封禁用户失败，{}", e.getMessage(), e);
            return false;
        }
    }

    public boolean unBanUserById(AbsSender sender, Long userId, String chatId) {
        UnbanChatMember unbanChatMember = new UnbanChatMember();
        unbanChatMember.setChatId(chatId);
        unbanChatMember.setUserId(userId);
        try {
            sender.execute(unbanChatMember);
            return true;
        } catch (TelegramApiException e) {
            log.error("解封用户失败，{}", e.getMessage(), e);
            return false;
        }
    }

    private void learnFromRepliedMessage(Update update, String chatId, String userId, String source) {
        Message repliedMessage = update.getMessage().getReplyToMessage();
        if (repliedMessage == null) {
            return;
        }

        String sampleText = repliedMessage.getText();
        if (!StringUtils.hasText(sampleText)) {
            sampleText = repliedMessage.getCaption();
        }
        if (!StringUtils.hasText(sampleText)) {
            return;
        }

        adLearningService.recordManualSpam(chatId, userId, sampleText, source);
    }

    private void deleteMessageQuietly(AbsSender sender, String chatId, Integer messageId, String errorMessage) {
        try {
            sender.execute(new DeleteMessage(chatId, messageId));
        } catch (TelegramApiException e) {
            log.warn("{}，chatId={}, messageId={}, error={}", errorMessage, chatId, messageId, e.getMessage());
        }
    }
}
