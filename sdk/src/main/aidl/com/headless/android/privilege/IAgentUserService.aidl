package com.headless.android.privilege;

interface IAgentUserService {
    String getDisplayReport();
    String getUiTreeJson(int displayId);
    void destroy();
}
