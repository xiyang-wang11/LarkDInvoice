package com.larkdinvoice.service;

public interface KingdeeAuthService {
    String getAppToken();
    String getAccessToken();
    void refreshTokens();
}
