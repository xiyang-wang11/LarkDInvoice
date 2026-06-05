package com.larkdinvoice.client;

import com.larkdinvoice.model.InvoiceRequest;
import com.larkdinvoice.model.InvoiceResult;

public interface KingdeeInvoiceClient {
    InvoiceResult createInvoice(InvoiceRequest request);
}
