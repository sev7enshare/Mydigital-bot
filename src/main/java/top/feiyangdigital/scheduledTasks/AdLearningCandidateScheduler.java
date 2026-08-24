package top.feiyangdigital.scheduledTasks;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.bots.AbsSender;
import top.feiyangdigital.bot.TgLongPollingBot;
import top.feiyangdigital.bot.TgWebhookBot;
import top.feiyangdigital.entity.BaseInfo;
import top.feiyangdigital.handleService.AdLearningRuleReviewService;

@Component
public class AdLearningCandidateScheduler {

    @Autowired
    private TgLongPollingBot tgLongPollingBot;

    @Autowired
    private TgWebhookBot tgWebhookBot;

    @Autowired
    private AdLearningRuleReviewService adLearningRuleReviewService;

    @Scheduled(cron = "0 30 9 * * *", zone = "Asia/Shanghai")
    public void sendDailyAdLearningCandidates() {
        sendCandidates();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void sendStartupAdLearningCandidates() {
        sendCandidates();
    }

    private void sendCandidates() {
        AbsSender sender = "webhook".equals(BaseInfo.getBotMode()) ? tgWebhookBot : tgLongPollingBot;
        adLearningRuleReviewService.sendDailyCandidates(sender);
    }
}
