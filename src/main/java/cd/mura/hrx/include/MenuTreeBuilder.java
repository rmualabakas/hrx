package cd.mura.hrx.include;

import cd.mura.hrx.entity.config.Menu;
import java.io.Serializable;
import java.util.*;

public class MenuTreeBuilder {

    public static final class MenuNode implements Serializable {
        private final Menu data;
        private final List<MenuNode> children = new ArrayList<>();
        public MenuNode(Menu data) { this.data = data; }
        public Menu getData() { return data; }
        public List<MenuNode> getChildren() { return children; }
        public boolean hasChildren() { return !children.isEmpty(); }
    }

    /** Construit l'arbre sur base de parentId (0 = racine) */
    public List<MenuNode> buildTree(Collection<Menu> flat) {
        Map<Integer, MenuNode> byId = new LinkedHashMap<>();
        for (Menu m : flat) {
            byId.put(m.getId(), new MenuNode(m));
        }

        List<MenuNode> roots = new ArrayList<>();
        for (Menu m : flat) {
            MenuNode node = byId.get(m.getId());
            int pid = m.getParentId();
            if (pid > 0 && byId.containsKey(pid)) {
                byId.get(pid).getChildren().add(node);
            } else {
                roots.add(node);
            }
        }

        sortRec(roots);
        return roots;
    }

    /** Tri simple : par nom puis par id (tu peux adapter) */
    private void sortRec(List<MenuNode> nodes) {
        nodes.sort(Comparator
                .comparing((MenuNode n) -> n.getData().getNom() == null ? "" : n.getData().getNom(), String.CASE_INSENSITIVE_ORDER)
                .thenComparingInt(n -> n.getData().getId()));
        for (MenuNode n : nodes) {
            sortRec(n.getChildren());
        }
    }
}
