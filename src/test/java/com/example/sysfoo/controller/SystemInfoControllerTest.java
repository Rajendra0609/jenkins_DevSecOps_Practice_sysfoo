package com.example.sysfoo.controller;

import com.example.sysfoo.service.SystemInfoService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// FIX: now that spring-boot-starter-security is on the classpath, @WebMvcTest
// auto-applies the Spring Security test filters. This slice test only cares
// about SystemInfoController's own logic, so security filters are disabled
// here rather than standing up the full SecurityConfig + a mock user.
@WebMvcTest(SystemInfoController.class)
@AutoConfigureMockMvc(addFilters = false)
public class SystemInfoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SystemInfoService systemInfoService;

    @Test
    public void getVersionTest() throws Exception {
        when(systemInfoService.getAppVersion()).thenReturn("1.0.0");
        mockMvc.perform(get("/version"))
               .andExpect(status().isOk())
               .andExpect(content().string("1.0.0"));
    }
}
