package com.larkdinvoice.handler;

import com.larkdinvoice.model.LarkEvent;

public interface ApprovalEventHandler {
    void handle(LarkEvent event);
}
