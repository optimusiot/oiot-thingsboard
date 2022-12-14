/**
 * Copyright © 2016-2022 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.service.notification;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.thingsboard.rule.engine.api.NotificationManager;
import org.thingsboard.server.common.data.alarm.Alarm;
import org.thingsboard.server.common.data.id.NotificationRuleId;
import org.thingsboard.server.common.data.id.NotificationTargetId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.notification.AlarmOriginatedNotificationInfo;
import org.thingsboard.server.common.data.notification.NotificationInfo;
import org.thingsboard.server.common.data.notification.NotificationOriginatorType;
import org.thingsboard.server.common.data.notification.NotificationRequest;
import org.thingsboard.server.common.data.notification.NotificationRequestConfig;
import org.thingsboard.server.common.data.notification.NotificationSeverity;
import org.thingsboard.server.common.data.notification.rule.NonConfirmedNotificationEscalation;
import org.thingsboard.server.common.data.notification.rule.NotificationRule;
import org.thingsboard.server.dao.notification.NotificationRequestService;
import org.thingsboard.server.dao.notification.NotificationRuleService;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.executors.DbCallbackExecutorService;

import java.util.List;
import java.util.Map;

@Service
@TbCoreComponent
@RequiredArgsConstructor
@Slf4j
public class DefaultNotificationRuleProcessingService implements NotificationRuleProcessingService {

    private final NotificationRuleService notificationRuleService;
    private final NotificationRequestService notificationRequestService;
    @Autowired @Lazy
    private NotificationManager notificationManager;
    private final DbCallbackExecutorService dbCallbackExecutorService;

    @Override
    public ListenableFuture<Void> onAlarmCreatedOrUpdated(TenantId tenantId, Alarm alarm) {
        return processAlarmUpdate(tenantId, alarm, false);
    }

    @Override
    public ListenableFuture<Void> onAlarmDeleted(TenantId tenantId, Alarm alarm) {
        return processAlarmUpdate(tenantId, alarm, true);
    }

    private ListenableFuture<Void> processAlarmUpdate(TenantId tenantId, Alarm alarm, boolean deleted) {
        if (alarm.getNotificationRuleId() == null) return Futures.immediateFuture(null);
        return dbCallbackExecutorService.submit(() -> {
            onAlarmUpdate(tenantId, alarm.getNotificationRuleId(), alarm, deleted);
            return null;
        });
    }

    @Override
    public ListenableFuture<Void> onNotificationRuleDeleted(TenantId tenantId, NotificationRuleId ruleId) {
        return dbCallbackExecutorService.submit(() -> {
            // need to remove fk constraint in notificationRequest to rule
            // todo: do we need to remove all notifications when notification request is deleted?
            return null;
        });
    }

    // todo: think about: what if notification rule was updated?
    private void onAlarmUpdate(TenantId tenantId, NotificationRuleId notificationRuleId, Alarm alarm, boolean deleted) {
        log.debug("Processing alarm update ({}) with notification rule {}", alarm.getId(), notificationRuleId);
        List<NotificationRequest> notificationRequests = notificationRequestService.findNotificationRequestsByRuleIdAndOriginatorEntityId(tenantId, notificationRuleId, alarm.getId());
        NotificationRule notificationRule = notificationRuleService.findNotificationRuleById(tenantId, notificationRuleId);
        if (notificationRule == null) return;

        if (alarmAcknowledged(alarm) || deleted) {
            for (NotificationRequest notificationRequest : notificationRequests) {
                notificationManager.deleteNotificationRequest(tenantId, notificationRequest.getId());
                // todo: or should we mark already sent notifications as read and delete only scheduled?
            }
            return;
        }

        if (notificationRequests.isEmpty()) {
            NotificationTargetId initialNotificationTargetId = notificationRule.getInitialNotificationTargetId();
            if (initialNotificationTargetId != null) {
                submitNotificationRequest(tenantId, initialNotificationTargetId, notificationRule, alarm, 0);
            }
            if (notificationRule.getEscalationConfig() != null) {
                for (NonConfirmedNotificationEscalation escalation : notificationRule.getEscalationConfig().getEscalations()) {
                    submitNotificationRequest(tenantId, escalation.getNotificationTargetId(), notificationRule, alarm, escalation.getDelayInSec());
                }
            }
        } else {
            NotificationInfo newNotificationInfo = constructNotificationInfo(alarm);
            for (NotificationRequest notificationRequest : notificationRequests) {
                NotificationInfo previousNotificationInfo = notificationRequest.getInfo();
                if (!previousNotificationInfo.equals(newNotificationInfo)) {
                    notificationRequest.setInfo(newNotificationInfo);
                    notificationManager.updateNotificationRequest(tenantId, notificationRequest);
                }
            }
        }
    }

    private boolean alarmAcknowledged(Alarm alarm) { // todo: decide when to consider the alarm processed by notification target (not to escalate then)
        return alarm.getStatus().isAck() && alarm.getStatus().isCleared();
    }

    private void submitNotificationRequest(TenantId tenantId, NotificationTargetId targetId, NotificationRule notificationRule, Alarm alarm, int delayInSec) {
        NotificationRequestConfig config = new NotificationRequestConfig();
        if (delayInSec > 0) {
            config.setSendingDelayInSec(delayInSec);
        }
        NotificationInfo notificationInfo = constructNotificationInfo(alarm);
        Map<String, String> templateContext = Map.of(
                "alarmType", alarm.getType(),
                "alarmId", alarm.getId().toString(),
                "alarmOriginatorEntityType", alarm.getOriginator().getEntityType().toString(),
                "alarmOriginatorId", alarm.getOriginator().getId().toString()
        );
        NotificationRequest notificationRequest = NotificationRequest.builder()
                .tenantId(tenantId)
                .targetId(targetId)
                .type("Alarm")
                .templateId(notificationRule.getTemplateId())
                .deliveryMethods(notificationRule.getDeliveryMethods())
                .additionalConfig(config)
                .info(notificationInfo)
                .originatorType(NotificationOriginatorType.ALARM)
                .originatorEntityId(alarm.getId())
                .ruleId(notificationRule.getId())
                .templateContext(templateContext)
                .build();
        notificationManager.processNotificationRequest(tenantId, notificationRequest);
    }

    private NotificationInfo constructNotificationInfo(Alarm alarm) {
        return AlarmOriginatedNotificationInfo.builder()
                .alarmId(alarm.getId())
                .alarmType(alarm.getType())
                .alarmOriginator(alarm.getOriginator())
                .alarmSeverity(alarm.getSeverity())
                .alarmStatus(alarm.getStatus())
                .build();
    }

}
