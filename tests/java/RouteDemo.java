import java.util.Arrays;
import java.util.Map;

/**
 * Calls the route-reconstruction helper on real dag() results and prints the evidence used in
 * ANALYSIS.md: route, chosen edge IDs, direction check and total weight versus the distance.
 * Exits with status 1 if any check fails.
 */
public final class RouteDemo {
    private static boolean allOk = true;

    private static void show(String title, String json, int target) {
        Map<String, Object> request = Json.parseRequest(json);
        int source = Input.source(request);
        int[][] edges = Input.edges(request);
        Map<String, Object> result = Algorithms.dag(request);
        int[] parent = (int[]) result.get("parent_edge");
        Long[] distance = (Long[]) result.get("distance");

        int[] route = Algorithms.reconstructRoute(edges, parent, source, target);
        int[] ids = Algorithms.reconstructRouteEdges(edges, parent, source, target);

        System.out.println("## " + title);
        System.out.println("request       : " + json);
        System.out.println("distance      : " + Arrays.toString(distance));
        System.out.println("parent_edge   : " + Arrays.toString(parent));
        System.out.println("target        : " + target + "  (source = " + source + ")");
        System.out.println("route         : " + Arrays.toString(route));

        boolean ok;
        if (distance[target] == null) {
            ok = route.length == 0 && ids == null;
            System.out.println("edge ids      : null (unreachable)  -> helper returns an EMPTY route");
            System.out.println("check         : empty route for an unreachable target = " + ok);
        } else {
            StringBuilder steps = new StringBuilder();
            long total = 0;
            ok = route.length == ids.length + 1 && route[0] == source && route[route.length - 1] == target;
            for (int i = 0; i < ids.length; i++) {
                int[] e = edges[ids[i]];
                ok &= e[0] == route[i] && e[1] == route[i + 1]; // direction: tail first, head second
                total += e[2];
                steps.append(i > 0 ? ", " : "").append("#").append(ids[i]).append(" = ")
                        .append(e[0]).append("->").append(e[1]).append(" (w=").append(e[2]).append(")");
            }
            ok &= total == distance[target];
            System.out.println("edge ids      : " + Arrays.toString(ids) + (ids.length == 0 ? " (no edges, the source itself)" : "  ["
                    + steps + "]"));
            System.out.println("check         : directions correct, total weight " + total + " == distance " + distance[target]
                    + " -> " + ok);
        }
        allOk &= ok;
        System.out.println();
    }

    public static void main(String[] args) {
        String example = "{\"task\":\"dag\",\"n\":5,\"source\":0,\"edges\":[[0,1,3],[0,2,8],[1,2,-7],[2,3,4]]}";
        show("reachable target (assignment example, target 3)", example, 3);
        show("the source itself (target 0)", example, 0);
        show("unreachable target (vertex 4)", example, 4);

        show("parallel edges: ids 0 and 1 are equally cheap (5), id 2 is dearer (9)",
                "{\"task\":\"dag\",\"n\":3,\"source\":0,\"edges\":[[0,1,5],[0,1,5],[0,1,9],[1,2,1]]}", 2);
        show("equal shortest paths: 0-1-3 and 0-2-3 both cost 2",
                "{\"task\":\"dag\",\"n\":4,\"source\":0,\"edges\":[[0,1,1],[1,3,1],[0,2,1],[2,3,1]]}", 3);
        show("source in the middle, negative weights",
                "{\"task\":\"dag\",\"n\":6,\"source\":2,\"edges\":[[0,2,9],[2,3,-4],[3,4,2],[2,4,1],[4,5,-6]]}", 5);

        System.out.println(allOk ? "ALL ROUTE CHECKS PASSED" : "ROUTE CHECK FAILED");
        if (!allOk) {
            System.exit(1);
        }
    }
}
