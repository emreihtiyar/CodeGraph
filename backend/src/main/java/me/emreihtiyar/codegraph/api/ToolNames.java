package me.emreihtiyar.codegraph.api;

/** MCP araç adları; REST uç noktaları ile MCP araçları arasındaki eşlemenin tek kaynağı. */
public final class ToolNames {

    public static final String LIST_ANALYSIS_RUNS = "list_analysis_runs";
    public static final String GET_ANALYSIS_REPORT = "get_analysis_report";
    public static final String LIST_SERVICES = "list_services";
    public static final String GET_SERVICE_DEPENDENCIES = "get_service_dependencies";
    public static final String LIST_SCREENS = "list_screens";
    public static final String LIST_ENDPOINTS = "list_endpoints";
    public static final String TRACE_FORWARD = "trace_forward";
    public static final String TRACE_BACKWARD = "trace_backward";
    public static final String GET_ENDPOINT_ORCHESTRATION = "get_endpoint_orchestration";
    public static final String FIND_TABLE_USAGES = "find_table_usages";
    public static final String GET_NODE = "get_node";
    public static final String GET_CODE = "get_code";
    public static final String SEARCH = "search";
    public static final String IMPACT_ANALYSIS = "impact_analysis";
    public static final String GET_CALL_TREE = "get_call_tree";

    private ToolNames() {}
}
