import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.List;

public class Server {
    // max capacity 50
    private static final SkipListLeaderboard leaderboard = new SkipListLeaderboard(50);

    public static void main(String[] args) throws IOException {
        leaderboard.zadd("Valkyrie", 3200);
        leaderboard.zadd("ApexPredator", 2800);
        leaderboard.zadd("ShadowStrike", 1200);
        leaderboard.setString("Valkyrie", "Elite Player Profile");
        leaderboard.hset("Valkyrie", "region", "US-East");

        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/leaderboard", new LeaderboardHandler());
        server.setExecutor(null);
        server.start();
        System.out.println("Redis-backed SkipList Cache Engine running on port 8080...");
    }

    static class LeaderboardHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // 1. CORS headers
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");

            // 2. Preflight
            if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            // 3. Query
            String query = exchange.getRequestURI().getQuery();
            String responseJson = "";

            if (exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                Map<String, String> params = parseQuery(query);
                String action = params.getOrDefault("action", "zadd");
                String id = params.get("id");
                if (id == null) {
                    sendResponse(exchange, 400, "{\"error\":\"Missing id parameter\"}");
                    return;
                }
                switch (action) {
                    case "zadd": {
                        int score = Integer.parseInt(params.getOrDefault("score", "1000"));
                        leaderboard.zadd(id, score);
                        responseJson = "{\"status\":\"success\",\"action\":\"zadd\",\"id\":\"" + id + "\"}";
                        break;
                    }
                    case "expire": {
                        long seconds = Long.parseLong(params.getOrDefault("seconds", "60"));
                        leaderboard.setExpire(id, seconds);
                        responseJson = "{\"status\":\"success\",\"action\":\"expire\",\"seconds\":" + seconds + "}";
                        break;
                    }
                    case "string": {
                        String val = params.getOrDefault("val", "");
                        leaderboard.setString(id, val);
                        responseJson = "{\"status\":\"success\",\"action\":\"string\"}";
                        break;
                    }
                    case "lpush": {
                        String val = params.getOrDefault("val", "");
                        leaderboard.lpush(id, val);
                        responseJson = "{\"status\":\"success\",\"action\":\"lpush\"}";
                        break;
                    }
                    case "hset": {
                        String field = params.getOrDefault("field", "k");
                        String val = params.getOrDefault("val", "v");
                        leaderboard.hset(id, field, val);
                        responseJson = "{\"status\":\"success\",\"action\":\"hset\"}";
                        break;
                    }
                    default:
                        break;
                }
            } else if (exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                Map<String, String> params = parseQuery(query);
                if (params.containsKey("stats")) {
                    responseJson = mapToJson(leaderboard.getCacheStats());
                } else if (params.containsKey("id")) {
                    String id = params.get("id");
                    Map<String, Object> info = leaderboard.getPlayerInfo(id);
                    if (info == null) {
                        responseJson = "{\"error\":\"Player/Key not found or expired\"}";
                    } else {
                        responseJson = mapToJson(info);
                    }
                } else {
                    responseJson = nestedMapToJson(leaderboard.getAllTiersTop(10));
                }
            }

            sendResponse(exchange, 200, responseJson);
        }

        private void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] bytes = response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statusCode, bytes.length);
            OutputStream os = exchange.getResponseBody();
            os.write(bytes);
            os.close();
        }

        private Map<String, String> parseQuery(String query) {
            Map<String, String> result = new java.util.HashMap<>();
            if (query == null) {
                return result;
            }
            for (String param : query.split("&")) {
                String[] pair = param.split("=");
                if (pair.length > 1) {
                    result.put(pair[0], java.net.URLDecoder.decode(pair[1], java.nio.charset.StandardCharsets.UTF_8));
                } else {
                    result.put(pair[0], "");
                }
            }
            return result;
        }

        @SuppressWarnings("unchecked")
        private String mapToJson(Map<String, Object> map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                if (!first) {
                    sb.append(",");
                }
                first = false;
                sb.append("\"").append(entry.getKey()).append("\":");
                Object value = entry.getValue();
                if (value instanceof String) {
                    sb.append("\"").append(value).append("\"");
                } else if (value instanceof Number || value instanceof Boolean) {
                    sb.append(value);
                } else if (value instanceof List) {
                    sb.append(listToJson((List<?>) value));
                } else if (value instanceof Map) {
                    sb.append(mapToJson((Map<String, Object>) value));
                } else {
                    sb.append("\"").append(value).append("\"");
                }
            }
            sb.append("}");
            return sb.toString();
        }

        private String listToJson(List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(",");
                }
                sb.append("\"").append(list.get(i)).append("\"");
            }
            sb.append("]");
            return sb.toString();
        }

        private String nestedMapToJson(Map<String, List<Map<String, Object>>> map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, List<Map<String, Object>>> entry : map.entrySet()) {
                if (!first) {
                    sb.append(",");
                }
                first = false;
                sb.append("\"").append(entry.getKey()).append("\":[");
                List<Map<String, Object>> items = entry.getValue();
                for (int i = 0; i < items.size(); i++) {
                    if (i > 0) {
                        sb.append(",");
                    }
                    sb.append(mapToJson(items.get(i)));
                }
                sb.append("]");
            }
            sb.append("}");
            return sb.toString();
        }
    }
}