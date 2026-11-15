package vn.teasmart.backend.payment;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.util.LinkedMultiValueMap;
import vn.teasmart.backend.config.PaymentSchedulingConfig;
import vn.teasmart.backend.config.ReviewImageCleanupConfig;
import vn.teasmart.backend.controller.PaymentController;
import vn.teasmart.backend.enums.PaymentStatus;
import vn.teasmart.backend.service.*;
import vn.teasmart.backend.storage.ReviewImageStorage;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VnpayReconciliationJobTest {
    private ApplicationContextRunner context(PaymentReconciliationService reconciliation, ReviewImageStorage storage) {
        return new ApplicationContextRunner()
                .withUserConfiguration(PaymentSchedulingConfig.class, ReviewImageCleanupConfig.class,
                        VnpayReconciliationJob.class)
                .withBean(PaymentReconciliationService.class, () -> reconciliation)
                .withBean(ReviewImageStorage.class, () -> storage);
    }

    @Test void missingFlagRegistersAndRunsJobByDefault() {
        assertEnabled(null);
    }

    @Test void explicitTrueRegistersAndRunsJob() {
        assertEnabled("true");
    }

    private void assertEnabled(String value) {
        var service = mock(PaymentReconciliationService.class);
        var runner = context(service, mock(ReviewImageStorage.class));
        if (value != null) runner = runner.withPropertyValues("teasmart.vnpay.reconciliation-enabled=" + value);
        runner.run(ctx -> {
            assertNull(ctx.getStartupFailure());
            assertEquals(1, ctx.getBeansOfType(VnpayReconciliationJob.class).size());
            assertEquals(2, ctx.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks().size());
            verify(service, timeout(3000)).reconcileExpired();
        });
    }

    @Test void falseDoesNotRegisterPaymentTaskButKeepsReviewCleanup() {
        var service = mock(PaymentReconciliationService.class);
        var storage = mock(ReviewImageStorage.class);
        context(service, storage).withPropertyValues("teasmart.vnpay.reconciliation-enabled=false").run(ctx -> {
            assertNull(ctx.getStartupFailure());
            assertTrue(ctx.getBeansOfType(VnpayReconciliationJob.class).isEmpty());
            assertSame(service, ctx.getBean(PaymentReconciliationService.class));
            assertEquals(1, ctx.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks().size());
            verifyNoInteractions(service);
            ctx.getBean(ReviewImageCleanupConfig.class).retryConfirmedDeletes();
            verify(storage).retryDeletes();
        });
    }

    @Test void disabledJobStillAllowsManualReconciliationThroughPaymentController() {
        var payments = mock(PaymentService.class);
        var query = mock(VnpayQueryClient.class);
        var service = new PaymentReconciliationService(payments, query);
        var snapshot = new PaymentService.Snapshot(190L, "test-only", PaymentTime.now(), PaymentStatus.PENDING);
        when(payments.snapshot(190L)).thenReturn(snapshot);
        context(service, mock(ReviewImageStorage.class))
                .withPropertyValues("teasmart.vnpay.reconciliation-enabled=false").run(ctx -> {
                    assertNull(ctx.getStartupFailure());
                    verifyNoInteractions(payments, query);
                    var controller = new PaymentController(payments, mock(VnpayGateway.class),
                            ctx.getBean(PaymentReconciliationService.class));
                    controller.reconcile(190L, null);
                    verify(query).query(snapshot);
                    verify(payments).unresolved(190L);
                    verify(payments).adminStatus(190L);
                    verify(payments, never()).due();
                });
    }

    @Test void disabledJobDoesNotChangeVerifiedCallbackPath() {
        var payments = mock(PaymentService.class);
        var gateway = mock(VnpayGateway.class);
        var service = mock(PaymentReconciliationService.class);
        var params = new LinkedMultiValueMap<String, String>();
        var notification = new VnpayGateway.Notification("test-only", BigDecimal.TEN,
                "00", "00", "test-transaction", PaymentTime.now());
        when(gateway.verifyIpn(params)).thenReturn(notification);
        when(payments.accept(notification, false)).thenReturn("00");
        context(service, mock(ReviewImageStorage.class))
                .withPropertyValues("teasmart.vnpay.reconciliation-enabled=false").run(ctx -> {
                    var controller = new PaymentController(payments, gateway, ctx.getBean(PaymentReconciliationService.class));
                    assertEquals("00", controller.ipn(params).get("RspCode"));
                    verify(gateway).verifyIpn(params);
                    verify(payments).accept(notification, false);
                    verifyNoInteractions(service);
                });
    }
}
