package com.ecommerce.notification.api;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.ecommerce.notification.application.AlertBroadcaster;

@WebMvcTest(AlertStreamController.class)
class AlertStreamControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AlertBroadcaster broadcaster;

    @Test
    void shouldOpenEventStream_whenAdminConnects() throws Exception {
        given(broadcaster.connect()).willReturn(new SseEmitter(0L));

        mvc.perform(get("/api/v1/alerts/stream").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());
    }
}
