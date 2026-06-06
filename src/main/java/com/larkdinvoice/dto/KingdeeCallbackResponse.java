package com.larkdinvoice.dto;

import lombok.Data;

/** 金蝶回调必须返回此格式，否则金蝶会无限重试 */
@Data
public class KingdeeCallbackResponse {
    private Boolean success;
    private String code;
    private String message;

    public static KingdeeCallbackResponse ok() {
        KingdeeCallbackResponse r = new KingdeeCallbackResponse();
        r.success = true;
        r.code = "0";
        r.message = "success";
        return r;
    }
}
