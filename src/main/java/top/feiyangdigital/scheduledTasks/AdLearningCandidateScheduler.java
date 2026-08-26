package top.feiyangdigital.scheduledTasks;

import lombok.extern.slf4j.Slf4j;
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
@Slf4j
public class AdLearningCandidateScheduler {

    @Autowired
    private TgLongPollingBot tgLongPollingBot;

    @Autowired
    private TgWebhookBot tgWebhookBot;

    @Autowired
    private AdLearningRuleReviewService adLearningRuleReviewService;

    @Scheduled(cron = "0 30 9 * * *", zone = "Asia/Shanghai")
    public void sendDailyAdLearningCandidates() {
        sendCandidates("daily");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void sendStartupAdLearningCandidates() {
        Thread startupSender = new Thread(() -> {
            try {
                Thread.sleep(30000L);
                sendCandidates("startup");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("广告学习启动候选规则推送被中断");
            }
        }, "ad-learning-startup-review");
        startupSender.setDaemon(true);
        startupSender.start();
    }

    private void sendCandidates(String trigger) {
        AbsSender sender = "webhook".equals(BaseInfo.getBotMode()) ? tgWebhookBot : tgLongPollingBot;
        int sentCount = adLearningRuleReviewService.sendDailyCandidates(sender);
        log.info("广告学习候选规则推送触发完成，trigger={}, sentCandidates={}", trigger, sentCount);
    }
}
