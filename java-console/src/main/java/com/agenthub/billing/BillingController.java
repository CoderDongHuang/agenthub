package com.agenthub.billing;

import com.agenthub.common.response.ApiResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/billing")
public class BillingController {

    private final JdbcTemplate jdbc;

    public BillingController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/usage")
    public ApiResponse<Map<String, Object>> usage() {
        Map<String, Object> monthUsage = jdbc.queryForMap(
                "SELECT COALESCE(SUM(input_tokens),0) AS monthly_input, " +
                        "COALESCE(SUM(output_tokens),0) AS monthly_output, COALESCE(SUM(cost),0) AS monthly_cost, " +
                        "COUNT(*) AS calls FROM token_usage WHERE created_at >= date_trunc('month', NOW())"
        );
        List<Map<String, Object>> byAgent = jdbc.queryForList(
                "SELECT usage.agent_id, COALESCE(agent.name, 'Unknown Agent') AS agent_name, COUNT(*) AS calls, " +
                        "SUM(usage.input_tokens + usage.output_tokens) AS total_tokens, SUM(usage.cost) AS total_cost " +
                        "FROM token_usage usage LEFT JOIN agent_definition agent ON agent.id = usage.agent_id " +
                        "WHERE usage.created_at >= date_trunc('month', NOW()) GROUP BY usage.agent_id, agent.name " +
                        "ORDER BY total_cost DESC LIMIT 20"
        );
        List<Map<String, Object>> byModel = jdbc.queryForList(
                "SELECT model, COUNT(*) AS calls, SUM(input_tokens + output_tokens) AS total_tokens, " +
                        "SUM(cost) AS total_cost FROM token_usage WHERE created_at >= date_trunc('month', NOW()) " +
                        "GROUP BY model ORDER BY total_cost DESC"
        );
        List<Map<String, Object>> daily = jdbc.queryForList(
                "WITH days AS (SELECT generate_series(CURRENT_DATE - INTERVAL '6 days', CURRENT_DATE, INTERVAL '1 day')::date AS day) " +
                        "SELECT to_char(days.day, 'MM-DD') AS day, COALESCE(SUM(t.input_tokens + t.output_tokens),0) AS tokens, " +
                        "COALESCE(SUM(t.cost),0) AS cost, COUNT(t.id) AS calls FROM days LEFT JOIN token_usage t " +
                        "ON t.created_at >= days.day AND t.created_at < days.day + INTERVAL '1 day' " +
                        "GROUP BY days.day ORDER BY days.day"
        );

        Map<String, Object> result = new LinkedHashMap<>(monthUsage);
        result.put("byAgent", byAgent);
        result.put("byModel", byModel);
        result.put("last7Days", daily);
        return ApiResponse.ok(result);
    }

    @GetMapping("/budget")
    public ApiResponse<Map<String, Object>> budget() {
        String month = YearMonth.now().toString();
        List<Map<String, Object>> budgets = jdbc.queryForList(
                "SELECT id, tenant_id, month, total_budget, alert_threshold FROM budget WHERE tenant_id = 0 AND month = ?",
                month
        );
        if (budgets.isEmpty()) {
            return ApiResponse.ok(Map.of(
                    "month", month,
                    "total_budget", BigDecimal.valueOf(1000),
                    "alert_threshold", BigDecimal.valueOf(0.8)
            ));
        }
        return ApiResponse.ok(budgets.get(0));
    }

    @PutMapping("/budget")
    public ApiResponse<Map<String, Object>> updateBudget(@RequestBody Map<String, Object> body) {
        String month = body.getOrDefault("month", YearMonth.now().toString()).toString();
        BigDecimal totalBudget = new BigDecimal(body.getOrDefault("totalBudget", "1000").toString());
        BigDecimal alertThreshold = new BigDecimal(body.getOrDefault("alertThreshold", "0.8").toString());
        if (totalBudget.compareTo(BigDecimal.ZERO) <= 0) {
            return ApiResponse.error(400, "Budget must be greater than zero");
        }
        if (alertThreshold.compareTo(BigDecimal.ZERO) <= 0 || alertThreshold.compareTo(BigDecimal.ONE) > 0) {
            return ApiResponse.error(400, "Alert threshold must be between 0 and 1");
        }
        jdbc.update(
                "INSERT INTO budget (tenant_id, month, total_budget, alert_threshold) VALUES (0,?,?,?) " +
                        "ON CONFLICT (tenant_id, month) DO UPDATE SET total_budget = EXCLUDED.total_budget, " +
                        "alert_threshold = EXCLUDED.alert_threshold",
                month, totalBudget, alertThreshold
        );
        return budget();
    }
}
