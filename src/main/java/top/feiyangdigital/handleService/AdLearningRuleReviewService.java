package top.feiyangdigital.handleService;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.bots.AbsSender;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import top.feiyangdigital.entity.GroupInfoWithBLOBs;
import top.feiyangdigital.scheduledTasks.HandleOption;
import top.feiyangdigital.sqlService.AdLearningService;
import top.feiyangdigital.sqlService.GroupInfoService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
@Service
public class AdLearningRuleReviewService {

    private static final int MIN_HIT_COUNT = 3;
    private static final int MIN_SPAM_CHANCE = 8;
    private static final int MAX_TOTAL_CANDIDATES = 50;
    private static final int MAX_GROUP_CANDIDATES = 5;

    @Autowired
    private AdLearningService adLearningService;

    @Autowired
    private GroupInfoService groupInfoService;

    @Autowired
    private HandleOption handleOption;

    public void sendDailyCandidates(AbsSender sender) {
        List<AdLearningService.AdLearningCandidate> candidates =
                adLearningService.listPendingRuleCandidates(MIN_HIT_COUNT, MIN_SPAM_CHANCE, MAX_TOTAL_CANDIDATES);
        Map<String, Integer> sentByGroup = new LinkedHashMap<>();

        for (AdLearningService.AdLearningCandidate candidate : candidates) {
            int groupSent = sentByGroup.getOrDefault(candidate.getGroupId(), 0);
            if (groupSent >= MAX_GROUP_CANDIDATES) {
                continue;
            }

            try {
                sender.execute(buildCandidateMessage(candidate));
                adLearningService.markCandidateSuggested(candidate.getId());
                sentByGroup.put(candidate.getGroupId(), groupSent + 1);
            } catch (TelegramApiException e) {
                log.warn("发送广告学习候选规则失败，groupId={}, sampleId={}, error={}",
                        candidate.getGroupId(), candidate.getId(), e.getMessage());
            }
        }
    }

    public void approveCandidate(AbsSender sender, Update update, long candidateId) throws TelegramApiException {
        String groupId = update.getCallbackQuery().getMessage().getChatId().toString();
        String reviewerId = update.getCallbackQuery().getFrom().getId().toString();
        AdLearningService.AdLearningCandidate candidate = adLearningService.findCandidate(candidateId, groupId);

        if (candidate == null) {
            editCallbackMessage(sender, update, "⚠️候选样本不存在，可能已经被处理。");
            return;
        }

        String rule = buildDeleteRule(candidate.getSampleText());
        if (!StringUtils.hasText(rule)) {
            adLearningService.markCandidateIgnored(candidateId, groupId, reviewerId);
            editCallbackMessage(sender, update, "⚠️样本文本太短，无法生成稳定规则，已忽略。");
            return;
        }

        GroupInfoWithBLOBs groupInfo = groupInfoService.selAllByGroupId(groupId);
        if (groupInfo == null) {
            editCallbackMessage(sender, update, "❌当前群组未初始化，无法写入规则。");
            return;
        }

        String storedRule = UUID.randomUUID() + " | " + rule;
        String oldKeywords = groupInfo.getKeywords();
        String newKeywords = StringUtils.hasText(oldKeywords) ? oldKeywords + "\n\n" + storedRule : storedRule;
        GroupInfoWithBLOBs updateRecord = new GroupInfoWithBLOBs();
        updateRecord.setKeywords(newKeywords);

        if (!groupInfoService.updateSelectiveByChatId(updateRecord, groupId)) {
            editCallbackMessage(sender, update, "❌规则写入失败，请稍后重试。");
            return;
        }

        adLearningService.markCandidateApproved(candidateId, groupId, reviewerId, rule);
        handleOption.ruleHandle(sender, groupId, groupInfo.getGroupname(), newKeywords);
        editCallbackMessage(sender, update, "✅已加入群规则并刷新生效。\n\n<code>" + escapeHtml(rule) + "</code>");
    }

    public void ignoreCandidate(AbsSender sender, Update update, long candidateId) throws TelegramApiException {
        String groupId = update.getCallbackQuery().getMessage().getChatId().toString();
        String reviewerId = update.getCallbackQuery().getFrom().getId().toString();
        if (adLearningService.markCandidateIgnored(candidateId, groupId, reviewerId)) {
            editCallbackMessage(sender, update, "✅已忽略该候选规则。");
        } else {
            editCallbackMessage(sender, update, "⚠️候选样本不存在或已经被处理。");
        }
    }

    private SendMessage buildCandidateMessage(AdLearningService.AdLearningCandidate candidate) {
        SendMessage message = new SendMessage();
        message.setChatId(candidate.getGroupId());
        message.setParseMode("HTML");
        message.setText("📌<b>广告学习候选规则</b>\n" +
                "样本ID：<code>" + candidate.getId() + "</code>\n" +
                "命中次数：<b>" + candidate.getHitCount() + "</b>\n" +
                "置信度：<b>" + candidate.getSpamChance() + "/10</b>\n" +
                "来源：<code>" + escapeHtml(candidate.getSource()) + "</code>\n\n" +
                "<b>样本内容：</b>\n<code>" + escapeHtml(shorten(candidate.getSampleText(), 500)) + "</code>\n\n" +
                "管理员确认后会自动加入本群删除规则。");

        InlineKeyboardButton approve = new InlineKeyboardButton();
        approve.setText("✅加入规则");
        approve.setCallbackData("adLearnOk:" + candidate.getId());

        InlineKeyboardButton ignore = new InlineKeyboardButton();
        ignore.setText("❌忽略");
        ignore.setCallbackData("adLearnNo:" + candidate.getId());

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(Arrays.asList(Arrays.asList(approve, ignore)));
        message.setReplyMarkup(markup);
        return message;
    }

    private String buildDeleteRule(String sampleText) {
        if (!StringUtils.hasText(sampleText)) {
            return "";
        }

        String cleaned = sampleText.replaceAll("[\\r\\n]+", " ").trim();
        if (cleaned.length() > 90) {
            cleaned = cleaned.substring(0, 90).trim();
        }

        String[] tokens = cleaned.split("\\s+");
        List<String> regexParts = new ArrayList<>();
        for (String token : tokens) {
            if (StringUtils.hasText(token)) {
                regexParts.add(Pattern.quote(token));
            }
        }
        if (regexParts.isEmpty()) {
            return "";
        }

        String regex = String.join("\\s*", regexParts);
        if (regex.length() < 6) {
            return "";
        }
        return regex + "===>广告学习规则命中，已删除&&del=x=0";
    }

    private void editCallbackMessage(AbsSender sender, Update update, String text) throws TelegramApiException {
        EditMessageText editMessageText = new EditMessageText();
        editMessageText.setChatId(update.getCallbackQuery().getMessage().getChatId().toString());
        editMessageText.setMessageId(update.getCallbackQuery().getMessage().getMessageId());
        editMessageText.setParseMode("HTML");
        editMessageText.setText(text);
        sender.execute(editMessageText);
    }

    private String shorten(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
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
