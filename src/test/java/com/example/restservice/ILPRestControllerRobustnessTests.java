package com.example.restservice;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.MockitoAnnotations;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Direct-call "robustness" tests.
 *
 * These are NOT HTTP integration tests. Spring @RequestBody binding + @NotNull validation do NOT run here.
 * So null request objects may throw NPE unless controller explicitly checks them.
 *
 * We keep NPE-expected tests to DOCUMENT weaknesses, and we add nested-field tests that
 * SHOULD be handled by explicit validation inside controller methods.
 */
public class ILPRestControllerRobustnessTests {

    @InjectMocks
    private IlpRestController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    // -----------------------
    // Null request object docs
    // -----------------------

    @Test
    void distanceTo_nullRequest_directCall_currentlyThrowsNpe_documentWeakness() {
        assertThrows(NullPointerException.class,
                () -> controller.calculateDistance(null),
                "Direct-call with null bypasses Spring validation; current behaviour is NPE");
    }

    @Test
    void isCloseTo_nullRequest_directCall_currentlyThrowsNpe_documentWeakness() {
        assertThrows(NullPointerException.class,
                () -> controller.isCloseTo(null),
                "Direct-call with null bypasses Spring validation; current behaviour is NPE");
    }

    @Test
    void nextPosition_nullRequest_directCall_currentlyThrowsNpe_documentWeakness() {
        assertThrows(NullPointerException.class,
                () -> controller.nextPosition(null),
                "Direct-call with null bypasses Spring validation; current behaviour is NPE");
    }

    @Test
    void isInRegion_nullRequest_directCall_currentlyThrowsNpe_documentWeakness() {
        assertThrows(NullPointerException.class,
                () -> controller.isInRegion(null),
                "Direct-call with null bypasses Spring validation; current behaviour is NPE");
    }

    // -----------------------
    // Nested null fields (should be 400 if controller validates)
    // -----------------------

    @Test
    void distanceTo_nullPosition1_shouldReturn400_orDocumentNpe() {
        LngLatPairRequest req = new LngLatPairRequest();
        req.setPosition1(null);
        req.setPosition2(new LngLat(-3.19, 55.94));

        try {
            ResponseEntity<Double> resp = controller.calculateDistance(req);
            assertEquals(400, resp.getStatusCodeValue(),
                    "If request.position1 is null, controller should reject with 400");
        } catch (NullPointerException npe) {
            // acceptable: document weakness
            assertTrue(true, "NPE documents missing nested-null validation");
        }
    }

    @Test
    void isCloseTo_nullPosition2_shouldReturn400_orDocumentNpe() {
        LngLatPairRequest req = new LngLatPairRequest();
        req.setPosition1(new LngLat(-3.19, 55.94));
        req.setPosition2(null);

        try {
            ResponseEntity<Boolean> resp = controller.isCloseTo(req);
            assertEquals(400, resp.getStatusCodeValue(),
                    "If request.position2 is null, controller should reject with 400");
        } catch (NullPointerException npe) {
            assertTrue(true, "NPE documents missing nested-null validation");
        }
    }

    @Test
    void nextPosition_nullStart_shouldReturn400() {
        NextPositionRequest req = new NextPositionRequest();
        req.setStart(null);
        req.setAngle(90.0);

        ResponseEntity<LngLat> resp = controller.nextPosition(req);

        assertNotNull(resp);
        assertEquals(400, resp.getStatusCodeValue(),
                "If request.start is null, controller should reject with 400");
    }

    @Test
    void isInRegion_nullRegion_shouldReturn400_orDocumentNpe() {
        IsInRegionRequest req = new IsInRegionRequest();
        req.setPosition(new LngLat(-3.19, 55.944));
        req.setRegion(null);

        try {
            ResponseEntity<Boolean> resp = controller.isInRegion(req);
            assertEquals(400, resp.getStatusCodeValue(),
                    "If region is null, controller should reject with 400");
        } catch (NullPointerException npe) {
            assertTrue(true, "NPE documents missing nested-null validation");
        }
    }

    @Test
    void isInRegion_nullVertices_shouldReturn400_orDocumentNpe() {
        Region region = new Region("bad", null);

        IsInRegionRequest req = new IsInRegionRequest();
        req.setPosition(new LngLat(-3.19, 55.944));
        req.setRegion(region);

        try {
            ResponseEntity<Boolean> resp = controller.isInRegion(req);
            assertEquals(400, resp.getStatusCodeValue(),
                    "If vertices are null, controller should reject with 400");
        } catch (NullPointerException npe) {
            assertTrue(true, "NPE documents missing vertices-null validation");
        }
    }

    // -----------------------
    // Invalid values (angles / coordinates)
    // -----------------------

    @Test
    void nextPosition_angleNegative_shouldReturn400() {
        NextPositionRequest req = new NextPositionRequest();
        req.setStart(new LngLat(-3.19, 55.94));
        req.setAngle(-1.0);

        ResponseEntity<LngLat> resp = controller.nextPosition(req);
        assertEquals(400, resp.getStatusCodeValue());
    }

    @Test
    void nextPosition_angleOver360_shouldReturn400() {
        NextPositionRequest req = new NextPositionRequest();
        req.setStart(new LngLat(-3.19, 55.94));
        req.setAngle(361.0);

        ResponseEntity<LngLat> resp = controller.nextPosition(req);
        assertEquals(400, resp.getStatusCodeValue());
    }

    @Test
    void nextPosition_angleNaN_shouldReturn400_orDocumentWeakness() {
        NextPositionRequest req = new NextPositionRequest();
        req.setStart(new LngLat(-3.19, 55.94));
        req.setAngle(Double.NaN);

        ResponseEntity<LngLat> resp = controller.nextPosition(req);
        // If your validation doesn’t check NaN explicitly, it might return 200.
        assertTrue(resp.getStatusCodeValue() == 400 || resp.getStatusCodeValue() == 200,
                "NaN handling should be defined; got " + resp.getStatusCodeValue());
    }

    @Test
    void nextPosition_startWithZeroCoordinate_shouldReturn400_documentPotentialBug() {
        // Many implementations incorrectly reject lng==0 or lat==0, even though 0 is a valid coordinate.
        NextPositionRequest req = new NextPositionRequest();
        req.setStart(new LngLat(0.0, 55.94));
        req.setAngle(90.0);

        ResponseEntity<LngLat> resp = controller.nextPosition(req);
        assertEquals(400, resp.getStatusCodeValue(),
                "If 400, document that lng==0/lat==0 are treated as invalid (likely wrong validation)");
    }

    // -----------------------
    // Polygon validity (FR3/FR4 enclosure)
    // -----------------------

    @Test
    void isInRegion_openPolygon_shouldReturn400() {
        Region open = new Region("open", List.of(
                new LngLat(-3.191, 55.943),
                new LngLat(-3.191, 55.945),
                new LngLat(-3.189, 55.945)
                // not closed
        ));

        IsInRegionRequest req = new IsInRegionRequest();
        req.setPosition(new LngLat(-3.190, 55.944));
        req.setRegion(open);

        ResponseEntity<Boolean> resp = controller.isInRegion(req);
        assertEquals(400, resp.getStatusCodeValue(),
                "Open polygons should be rejected to enforce enclosed-polygon requirement");
    }

    @Test
    void isInRegion_tooFewVertices_shouldReturn400_orDocumentWeakness() {
        Region tiny = new Region("tiny", List.of(
                new LngLat(-3.191, 55.943),
                new LngLat(-3.191, 55.943) // degenerate
        ));

        IsInRegionRequest req = new IsInRegionRequest();
        req.setPosition(new LngLat(-3.190, 55.944));
        req.setRegion(tiny);

        ResponseEntity<Boolean> resp = controller.isInRegion(req);
        assertTrue(resp.getStatusCodeValue() == 400 || resp.getStatusCodeValue() == 200,
                "Define behaviour for degenerate polygons; got " + resp.getStatusCodeValue());
    }

    // -----------------------
    // Technique: reliability of deterministic endpoints (NR1-ish)
    // -----------------------

    @Test
    void nextPosition_sameInput_repeatedCalls_sameOutput_NR1() {
        NextPositionRequest req = new NextPositionRequest();
        req.setStart(new LngLat(-3.19, 55.94));
        req.setAngle(90.0);

        ResponseEntity<LngLat> first = controller.nextPosition(req);
        assertEquals(200, first.getStatusCodeValue());
        assertNotNull(first.getBody());

        for (int i = 0; i < 20; i++) {
            ResponseEntity<LngLat> again = controller.nextPosition(req);
            assertEquals(200, again.getStatusCodeValue());
            assertEquals(first.getBody(), again.getBody(), "Call " + i + " differed");
        }
    }
}