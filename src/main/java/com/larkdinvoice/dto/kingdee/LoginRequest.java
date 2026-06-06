package com.larkdinvoice.dto.kingdee;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class LoginRequest {
    private String accountId;
    private String user;
    @JsonProperty("apptoken")
    private String appToken;
    private String loginType;
}
