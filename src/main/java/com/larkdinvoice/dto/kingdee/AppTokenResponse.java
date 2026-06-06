package com.larkdinvoice.dto.kingdee;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AppTokenResponse {

    private String state;
    private Boolean status;
    private DataBody data;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DataBody {
        @JsonProperty("app_token")
        private String appToken;
        private Boolean success;
        @JsonProperty("error_desc")
        private String errorDesc;
        @JsonProperty("error_code")
        private String errorCode;
        @JsonProperty("expire_time")
        private Long expireTime;
    }

    public String getAppToken() {
        return data != null ? data.getAppToken() : null;
    }

    public String getMessage() {
        return data != null ? data.getErrorDesc() : null;
    }
}
