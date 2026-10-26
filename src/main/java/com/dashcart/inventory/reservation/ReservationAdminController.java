package com.dashcart.inventory.reservation;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

// Lets an admin (or the load-test script) switch the checkout strategy at runtime.
@RestController
public class ReservationAdminController {

    private final ReservationStrategies strategies;

    public ReservationAdminController(ReservationStrategies strategies) {
        this.strategies = strategies;
    }

    @GetMapping("/admin/checkout/strategy")
    public StrategyResponse get() {
        return new StrategyResponse(strategies.activeType(), List.of(ReservationStrategyType.values()));
    }

    @PutMapping("/admin/checkout/strategy")
    public StrategyResponse set(@Valid @RequestBody StrategyRequest request) {
        strategies.activate(request.strategy());
        return get();
    }

    public record StrategyRequest(@NotNull ReservationStrategyType strategy) {
    }

    public record StrategyResponse(ReservationStrategyType active, List<ReservationStrategyType> available) {
    }
}
