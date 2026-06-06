package com.larkdinvoice.dto.kingdee;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class LoginResponse {

    private String state;
    private Boolean status;
    private DataBody data;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DataBody {
        @JsonProperty("access_token")
        private String accessToken;
        private Boolean success;
        @JsonProperty("error_desc")
        private String errorDesc;
        @JsonProperty("error_code")
        private String errorCode;
        @JsonProperty("expire_time")
        private Long expireTime;
    }

    public String getAccessToken() {
        return data != null ? data.getAccessToken() : null;
    }

    public String getMessage() {
        return data != null ? data.getErrorDesc() : null;
    }
}
