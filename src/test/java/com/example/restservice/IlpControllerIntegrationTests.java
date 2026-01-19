package com.example.restservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests: HTTP -> Controller -> (real app wiring) -> HTTP response.
 * Focus on JSON binding/validation and tolerant numeric checks.
 *
 * NOTE: Avoid duplicating pure system-smoke checks already in IlpSystemTests (e.g., /isAlive, malformed JSON 400).
 */
@SpringBootTest
@AutoConfigureMockMvc
class IlpControllerIntegrationTests {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    private static double euclidean(LngLat a, LngLat b) {
        double dx = a.getLng() - b.getLng();
        double dy = a.getLat() - b.getLat();
        return Math.sqrt(dx * dx + dy * dy);
    }

    // -------------------------
    // uuid (keep here if you want hard-coded assertion)
    // -------------------------

    @Test
    void uuid_overHttp_returns200AndUuidString() throws Exception {
        mockMvc.perform(get("/uuid"))
                .andExpect(status().isOk())
                .andExpect(content().string("s2298559"));
    }

    // -------------------------
    // distanceTo
    // -------------------------

    @Test
    void distanceTo_overHttp_validRequest_returns200AndNumber() throws Exception {
        LngLatPairRequest req = new LngLatPairRequest();
        req.setPosition1(new LngLat(-3.192473, 55.946233));
        req.setPosition2(new LngLat(-3.192473, 55.942617));

        mockMvc.perform(post("/distanceTo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isNumber());
    }

    @Test
    void distanceTo_overHttp_invalidCoordinates_returns400() throws Exception {
        LngLatPairRequest req = new LngLatPairRequest();
        req.setPosition1(new LngLat(-300.0, 55.946233));
        req.setPosition2(new LngLat(-3.192473, 100.0));

        mockMvc.perform(post("/distanceTo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void distanceTo_overHttp_missingFields_returns400() throws Exception {
        // Missing position2
        String body = """
          {"position1":{"lng":-3.192473,"lat":55.946233}}
        """;

        mockMvc.perform(post("/distanceTo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    // -------------------------
    // isCloseTo
    // -------------------------

    @Test
    void isCloseTo_overHttp_samePoint_returnsTrue() throws Exception {
        LngLatPairRequest req = new LngLatPairRequest();
        req.setPosition1(new LngLat(-3.192473, 55.946233));
        req.setPosition2(new LngLat(-3.192473, 55.946233));

        mockMvc.perform(post("/isCloseTo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().string("true"));
    }

    @Test
    void isCloseTo_overHttp_farPoints_returnsFalse() throws Exception {
        LngLatPairRequest req = new LngLatPairRequest();
        req.setPosition1(new LngLat(-3.192473, 55.946233));
        req.setPosition2(new LngLat(-3.192473, 55.942617));

        mockMvc.perform(post("/isCloseTo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(content().string("false"));
    }

    // -------------------------
    // nextPosition (FR2 + QR2) - add property-style + numeric tolerance
    // -------------------------

    @Test
    void nextPosition_overHttp_invalidAngle_returns400() throws Exception {
        NextPositionRequest req = new NextPositionRequest();
        req.setStart(new LngLat(-3.192473, 55.946233));
        req.setAngle(370.5);

        mockMvc.perform(post("/nextPosition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void nextPosition_overHttp_missingStart_returns400() throws Exception {
        String body = """
          {"angle":90.0}
        """;

        mockMvc.perform(post("/nextPosition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void nextPosition_overHttp_missingAngle_returns400() throws Exception {
        String body = """
          {"start":{"lng":-3.192473,"lat":55.946233}}
        """;

        mockMvc.perform(post("/nextPosition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void nextPosition_overHttp_wrongTypeAngle_returns400() throws Exception {
        String body = """
          {"start":{"lng":-3.192473,"lat":55.946233},"angle":"ninety"}
        """;

        mockMvc.perform(post("/nextPosition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void nextPosition_overHttp_stepLengthWithinTolerance_QR2() throws Exception {
        LngLat start = new LngLat(-3.192473, 55.946233);

        NextPositionRequest req = new NextPositionRequest();
        req.setStart(start);
        req.setAngle(90.0);

        String json = mockMvc.perform(post("/nextPosition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        LngLat end = objectMapper.readValue(json, LngLat.class);

        double d = euclidean(start, end);
        assertEquals(0.00015, d, 1e-9);
    }

    @Test
    void nextPosition_overHttp_all16Angles_propertyCheck() throws Exception {
        double[] angles = {
                22.5, 45.0, 67.5, 90.0, 112.5, 135.0, 157.5,
                180.0, 202.5, 225.0, 247.5, 270.0, 292.5, 315.0, 337.5, 360.0
        };

        LngLat start = new LngLat(-3.192473, 55.946233);

        for (double a : angles) {
            NextPositionRequest req = new NextPositionRequest();
            req.setStart(start);
            req.setAngle(a);

            var mvcResult = mockMvc.perform(post("/nextPosition")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    // spec says all 16 angles are allowed; if 0 fails, document it as a weakness
                    .andExpect(status().is2xxSuccessful())
                    .andReturn();

            LngLat end = objectMapper.readValue(mvcResult.getResponse().getContentAsString(), LngLat.class);
            double d = euclidean(start, end);
            assertEquals(0.00015, d, 1e-9, "Angle " + a + " produced wrong step length");
        }
    }

    // -------------------------
    // isInRegion (FR3/FR4 enclosure checks)
    // -------------------------

    @Test
    void isInRegion_overHttp_openPolygon_returns400() throws Exception {
        Region openRegion = new Region("open", java.util.List.of(
                new LngLat(-3.192473, 55.946233),
                new LngLat(-3.192473, 55.942617),
                new LngLat(-3.184319, 55.942617) // not closed
        ));

        IsInRegionRequest req = new IsInRegionRequest();
        req.setPosition(new LngLat(-3.184319, 55.942617));
        req.setRegion(openRegion);

        mockMvc.perform(post("/isInRegion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void isInRegion_overHttp_closedSquare_inside_returnsTrue() throws Exception {
        Region square = new Region("square", java.util.List.of(
                new LngLat(-3.191, 55.943),
                new LngLat(-3.191, 55.945),
                new LngLat(-3.189, 55.945),
                new LngLat(-3.189, 55.943),
                new LngLat(-3.191, 55.943)
        ));

        IsInRegionRequest req = new IsInRegionRequest();
        req.setPosition(new LngLat(-3.190, 55.944));
        req.setRegion(square);

        mockMvc.perform(post("/isInRegion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(content().string("true"));
    }

    @Test
    void isInRegion_overHttp_closedSquare_outside_returnsFalse() throws Exception {
        Region square = new Region("square", java.util.List.of(
                new LngLat(-3.191, 55.943),
                new LngLat(-3.191, 55.945),
                new LngLat(-3.189, 55.945),
                new LngLat(-3.189, 55.943),
                new LngLat(-3.191, 55.943)
        ));

        IsInRegionRequest req = new IsInRegionRequest();
        req.setPosition(new LngLat(-3.200, 55.950));
        req.setRegion(square);

        mockMvc.perform(post("/isInRegion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(content().string("false"));
    }

    // -------------------------
    // Content-type robustness (FR5/QR3)
    // -------------------------

    @Test
    void distanceTo_overHttp_wrongContentType_returns415Or400() throws Exception {
        mockMvc.perform(post("/distanceTo")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("hello"))
                .andExpect(result -> {
                    int s = result.getResponse().getStatus();
                    assertTrue(s == 415 || s == 400, "Expected 415 or 400 but got " + s);
                });
    }
}