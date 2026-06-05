package com.larkdinvoice.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class LarkEvent {

    private String challenge;
    private String token;
    private String type;

    private Schema schema;
    private Header header;
    private EventBody event;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Schema {
        private String schema;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Header {
        @JsonProperty("event_id")
        private String eventId;
        @JsonProperty("event_type")
        private String eventType;
        @JsonProperty("create_time")
        private String createTime;
        @JsonProperty("token")
        private String token;
        @JsonProperty("app_id")
        private String appId;
        @JsonProperty("tenant_key")
        private String tenantKey;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EventBody {
        @JsonProperty("approval_code")
        private String approvalCode;
        @JsonProperty("instance_code")
        private String instanceCode;
        @JsonProperty("status")
        private String status;
        @JsonProperty("user_id")
        private String userId;
        @JsonProperty("open_id")
        private String openId;
        @JsonProperty("form")
        private String form;
        @JsonProperty("timeline")
        private List<Map<String, Object>> timeline;
    }
}
