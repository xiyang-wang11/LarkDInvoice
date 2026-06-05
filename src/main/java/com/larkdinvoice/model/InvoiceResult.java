package com.larkdinvoice.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class InvoiceResult {

    @JsonProperty("success")
    private boolean success;

    @JsonProperty("invoiceNo")
    private String invoiceNo;

    @JsonProperty("errorCode")
    private String errorCode;

    @JsonProperty("errorMsg")
    private String errorMsg;
}
