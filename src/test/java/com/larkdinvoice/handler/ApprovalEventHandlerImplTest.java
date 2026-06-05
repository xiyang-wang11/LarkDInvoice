package com.larkdinvoice.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkdinvoice.client.KingdeeInvoiceClient;
import com.larkdinvoice.config.AppConfig;
import com.larkdinvoice.model.InvoiceResult;
import com.larkdinvoice.model.LarkEvent;
import com.larkdinvoice.service.LarkNotifyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApprovalEventHandlerImplTest {

    @Mock
    private AppConfig appConfig;
    @Mock
    private KingdeeInvoiceClient kingdeeInvoiceClient;
    @Mock
    private LarkNotifyService larkNotifyService;
    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private ApprovalEventHandlerImpl handler;

    private AppConfig.FormFields formFields;

    @BeforeEach
    void setUp() {
        formFields = new AppConfig.FormFields();
        formFields.setBuyerName("field_buyer_name");
        formFields.setBuyerTaxNo("field_buyer_tax_no");
        formFields.setBuyerAddressPhone("field_buyer_address_phone");
        formFields.setBuyerBankAccount("field_buyer_bank_account");
        formFields.setInvoiceType("field_invoice_type");
        formFields.setAmount("field_amount");
        formFields.setItems("field_items");

        when(appConfig.getApprovalCode()).thenReturn("test-approval-code");
        lenient().when(appConfig.getFormFields()).thenReturn(formFields);
    }

    @Test
    void shouldIgnoreNonTargetApprovalCode() {
        LarkEvent event = buildEvent("other-approval-code", "APPROVED");
        handler.handle(event);
        verifyNoInteractions(kingdeeInvoiceClient, larkNotifyService);
    }

    @Test
    void shouldIgnoreNonApprovedStatus() {
        LarkEvent event = buildEvent("test-approval-code", "PENDING");
        handler.handle(event);
        verifyNoInteractions(kingdeeInvoiceClient, larkNotifyService);
    }

    @Test
    void shouldNotifySuccessWhenInvoiceCreated() throws Exception {
        LarkEvent event = buildEvent("test-approval-code", "APPROVED");
        event.getEvent().setForm("[{\"id\":\"field_buyer_name\",\"value\":\"测试公司\"}]");

        List<Map<String, Object>> formList = buildFormList();
        when(objectMapper.readValue(any(String.class), any(com.fasterxml.jackson.core.type.TypeReference.class)))
                .thenReturn(formList);

        InvoiceResult result = new InvoiceResult();
        result.setSuccess(true);
        result.setInvoiceNo("INV-2026-001");
        when(kingdeeInvoiceClient.createInvoice(any())).thenReturn(result);

        handler.handle(event);

        verify(larkNotifyService).notifySuccess(eq("test-open-id"), eq("INV-2026-001"), any(BigDecimal.class));
    }

    @Test
    void shouldNotifyFailureWhenInvoiceFailed() throws Exception {
        LarkEvent event = buildEvent("test-approval-code", "APPROVED");
        event.getEvent().setForm("[{\"id\":\"field_buyer_name\",\"value\":\"测试公司\"}]");

        List<Map<String, Object>> formList = buildFormList();
        when(objectMapper.readValue(any(String.class), any(com.fasterxml.jackson.core.type.TypeReference.class)))
                .thenReturn(formList);

        InvoiceResult result = new InvoiceResult();
        result.setSuccess(false);
        result.setErrorMsg("金蝶接口错误");
        when(kingdeeInvoiceClient.createInvoice(any())).thenReturn(result);

        handler.handle(event);

        verify(larkNotifyService).notifyFailure(eq("test-open-id"), contains("金蝶接口错误"));
    }

    @Test
    void shouldSkipDuplicateInstanceCode() throws Exception {
        LarkEvent event = buildEvent("test-approval-code", "APPROVED");
        event.getEvent().setForm("[{\"id\":\"field_buyer_name\",\"value\":\"测试公司\"}]");

        List<Map<String, Object>> formList = buildFormList();
        when(objectMapper.readValue(any(String.class), any(com.fasterxml.jackson.core.type.TypeReference.class)))
                .thenReturn(formList);

        InvoiceResult result = new InvoiceResult();
        result.setSuccess(true);
        result.setInvoiceNo("INV-001");
        when(kingdeeInvoiceClient.createInvoice(any())).thenReturn(result);

        handler.handle(event);
        handler.handle(event);

        verify(kingdeeInvoiceClient, times(1)).createInvoice(any());
    }

    private LarkEvent buildEvent(String approvalCode, String status) {
        LarkEvent event = new LarkEvent();
        LarkEvent.EventBody body = new LarkEvent.EventBody();
        body.setApprovalCode(approvalCode);
        body.setInstanceCode("test-instance-001");
        body.setStatus(status);
        body.setOpenId("test-open-id");
        event.setEvent(body);
        return event;
    }

    private List<Map<String, Object>> buildFormList() {
        Map<String, Object> f1 = new HashMap<>(); f1.put("id", "field_buyer_name"); f1.put("value", "测试公司");
        Map<String, Object> f2 = new HashMap<>(); f2.put("id", "field_buyer_tax_no"); f2.put("value", "91110000123456789X");
        Map<String, Object> f3 = new HashMap<>(); f3.put("id", "field_buyer_address_phone"); f3.put("value", "北京市 010-12345678");
        Map<String, Object> f4 = new HashMap<>(); f4.put("id", "field_buyer_bank_account"); f4.put("value", "工商银行 6222001234567890");
        Map<String, Object> f5 = new HashMap<>(); f5.put("id", "field_invoice_type"); f5.put("value", "增值税专用发票");
        Map<String, Object> f6 = new HashMap<>(); f6.put("id", "field_amount"); f6.put("value", "10000.00");
        Map<String, Object> f7 = new HashMap<>(); f7.put("id", "field_items"); f7.put("value", "[]");
        return Arrays.asList(f1, f2, f3, f4, f5, f6, f7);
    }
}
