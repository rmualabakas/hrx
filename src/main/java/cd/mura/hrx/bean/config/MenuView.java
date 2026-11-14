package cd.mura.hrx.bean.config;

import cd.mura.hrx.dao.config.MenuDAO;
import cd.mura.hrx.dao.config.ModuleDAO;
import cd.mura.hrx.entity.config.Menu;
import cd.mura.hrx.entity.config.ModuleP;
import cd.mura.hrx.entity.config.Profil;
import cd.mura.hrx.entity.config.Users;
import cd.mura.hrx.include.MenuTreeBuilder;
import cd.mura.hrx.include.MenuTreeBuilder.MenuNode;
import jakarta.annotation.PostConstruct;
import jakarta.ejb.EJB;
import jakarta.enterprise.context.SessionScoped;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Named;
import lombok.Data;
import org.primefaces.model.menu.DefaultMenuItem;
import org.primefaces.model.menu.DefaultMenuModel;
import org.primefaces.model.menu.DefaultSubMenu;
import org.primefaces.model.menu.MenuModel;

import java.io.Serializable;
import java.util.*;

/**
 * Construit le menu latéral HRX (UL/LI), l'ancien rendu Charisma et, en option, un MenuModel PrimeFaces.
 * VERSION CORRIGÉE : Utilise FontAwesome au lieu de PrimeIcons pour les chevrons
 */
@Named("menuView")
@SessionScoped
@Data
public class MenuView implements Serializable {
    private static final long serialVersionUID = 1L;

    @EJB private ModuleDAO moduleDAO;
    @EJB private MenuDAO   menuDAO;

    private Users users;
    private List<ModuleP> modules;
    private final Map<Integer, List<MenuNode>> treeByModule = new LinkedHashMap<>();

    // Variante p:menu (non utilisée)
    private MenuModel pfModel;

    @PostConstruct
    public void init() {
        modules  = new ArrayList<>();
        pfModel  = new DefaultMenuModel();
    }

    /** À appeler après login. */
    public void loadFor(Users current) {
        this.users = current;
        Profil profil = current.getProfil();

        // 1) modules visibles
        modules = moduleDAO.findModulActifByProfil(profil);
        treeByModule.clear();

        // 2) menus à plat -> arbres regroupés par module
        MenuTreeBuilder builder = new MenuTreeBuilder();
        for (ModuleP mod : modules) {
            List<Menu> flat = menuDAO.findAllActifByProfilAndModule(profil, mod);
            List<MenuNode> roots = builder.buildTree(flat);
            treeByModule.put(mod.getId(), roots);
        }

        // 3) option PF
        buildPrimefacesModel();
    }

    // Rendu Charisma (UL/LI)

    /** HTML du menu Charisma; utiliser dans la vue avec escape="false". */
    public String getCharismaHtml() {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("<ul class=\"nav nav-pills nav-stacked main-menu\">");

        // Lien Tableau de bord
        sb.append("<li><a class=\"ajax-link\" href=\"")
                .append(ctx()).append("/ui/home.xhtml")
                .append("\"><i class=\"glyphicon glyphicon-home\"></i><span> Tableau de bord</span></a></li>");

        // Modules : rendus comme des dossiers (accordéons) avec chevron FontAwesome
        for (ModuleP mod : modules) {
            String modIcon = def(mod.getIcone(), "glyphicon glyphicon-th");

            sb.append("<li class=\"accordion\">");
            sb.append("<a href=\"#\">")
                    .append("<i class=\"").append(escape(modIcon)).append("\"></i>")
                    .append("<span> ").append(escape(mod.getNom())).append("</span>")
                    // UTILISER FA AU LIEU DE PI
                    .append("<i class=\"fa fa-angle-down\"></i>")
                    .append("</a>");

            sb.append("<ul class=\"nav nav-pills nav-stacked\">");
            List<MenuNode> roots = treeByModule.get(mod.getId());
            if (roots != null) {
                for (MenuNode n : roots) {
                    appendNodeHtml(sb, n);
                }
            }
            sb.append("</ul></li>");
        }

        sb.append("</ul>");
        return sb.toString();
    }

    /** Rend un node (feuille/dossier) au format Charisma. */
    private void appendNodeHtml(StringBuilder sb, MenuNode node) {
        Menu m = node.getData();
        String icon = def(m.getIcone(), "glyphicon glyphicon-chevron-right");

        if (!node.hasChildren()) {
            // Feuille -> lien simple
            sb.append("<li><a class=\"ajax-link\" href=\"")
                    .append(resolveUrl(m.getUrl()))
                    .append("\"><i class=\"").append(escape(icon)).append("\"></i>")
                    .append("<span> ").append(escape(m.getNom())).append("</span></a></li>");
        } else {
            // Dossier -> accordéon imbriqué avec chevron FontAwesome
            sb.append("<li class=\"accordion\">");
            sb.append("<a href=\"#\">")
                    .append("<i class=\"").append(escape(icon)).append("\"></i>")
                    .append("<span> ").append(escape(m.getNom())).append("</span>")
                    // UTILISER FA AU LIEU DE PI
                    .append("<i class=\"fa fa-angle-down\"></i>")
                    .append("</a>");

            sb.append("<ul class=\"nav nav-pills nav-stacked\">");
            for (MenuNode c : node.getChildren()) {
                appendNodeHtml(sb, c);
            }
            sb.append("</ul></li>");
        }
    }

    // Rendu HRX (sidebar premium)

    /** HTML de la sidebar HRX ; utiliser dans la vue avec escape="false". Classes stylées par hrx-shell.css. */
    public String getSidebarHtml() {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("<ul class=\"hx-nav\">");
        sb.append("<li class=\"hx-nav__module\"><ul>");
        sb.append("<li><a class=\"hx-nav__link\" href=\"").append(ctx()).append("/ui/home\">")
                .append("<i class=\"hx-nav__icon pi pi-home\"></i><span class=\"hx-nav__text\">Tableau de bord</span></a></li>");
        sb.append("</ul></li>");

        for (ModuleP mod : modules) {
            sb.append("<li class=\"hx-nav__module\">");
            sb.append("<span class=\"hx-nav__module-title\">").append(escape(mod.getNom())).append("</span>");
            sb.append("<ul>");
            List<MenuNode> roots = treeByModule.get(mod.getId());
            if (roots != null) for (MenuNode n : roots) appendNodeHrx(sb, n, "m" + mod.getId());
            sb.append("</ul></li>");
        }
        sb.append("</ul>");
        return sb.toString();
    }

    private void appendNodeHrx(StringBuilder sb, MenuNode node, String prefix) {
        Menu m = node.getData();
        String icon = toPrimeIcon(m.getIcone());
        String id = prefix + "-" + m.getId();
        if (!node.hasChildren()) {
            sb.append("<li><a class=\"hx-nav__link\" href=\"").append(resolveUrl(m.getUrl())).append("\">")
                    .append("<i class=\"hx-nav__icon ").append(escape(icon)).append("\"></i>")
                    .append("<span class=\"hx-nav__text\">").append(escape(m.getNom())).append("</span></a></li>");
        } else {
            sb.append("<li class=\"hx-nav__folder\" data-folder=\"").append(id).append("\">");
            sb.append("<button type=\"button\" class=\"hx-nav__link\" aria-expanded=\"false\">")
                    .append("<i class=\"hx-nav__icon ").append(escape(icon)).append("\"></i>")
                    .append("<span class=\"hx-nav__text\">").append(escape(m.getNom())).append("</span>")
                    .append("<i class=\"hx-nav__chevron pi pi-angle-down\"></i></button>");
            sb.append("<ul class=\"hx-nav__submenu\">");
            for (MenuNode c : node.getChildren()) appendNodeHrx(sb, c, id);
            sb.append("</ul></li>");
        }
    }

    /**
     * Les icônes stockées en base peuvent être au format Glyphicon/FontAwesome (héritage clix).
     * La coque HRX n'embarque que PrimeIcons : on convertit les cas courants, sinon icône générique.
     */
    private static String toPrimeIcon(String raw) {
        if (raw == null || raw.isBlank()) return "pi pi-circle";
        String s = raw.trim();
        if (s.startsWith("pi ")) return s;
        // Font Awesome est chargé localement : ses classes sont utilisées telles quelles.
        if (s.matches("^(fa|fas|far|fab|fa-solid|fa-regular|fa-brands)\\s+fa-[a-z0-9-]+.*")) return s;
        String key = s.replace("glyphicon glyphicon-", "").replace("fa fa-", "").replace("fas fa-", "").replace("fa-", "");
        return switch (key) {
            case "user", "users", "group" -> "pi pi-users";
            case "cog", "cogs", "wrench", "gear" -> "pi pi-cog";
            case "list", "list-alt", "th-list" -> "pi pi-list";
            case "calendar" -> "pi pi-calendar";
            case "file", "file-text", "file-o" -> "pi pi-file";
            case "edit", "pencil" -> "pi pi-pencil";
            case "lock", "shield" -> "pi pi-lock";
            case "stats", "bar-chart", "signal" -> "pi pi-chart-bar";
            case "dashboard", "tachometer" -> "pi pi-chart-line";
            case "sitemap" -> "pi pi-sitemap";
            case "folder", "folder-open" -> "pi pi-folder";
            case "th", "th-large" -> "pi pi-th-large";
            case "envelope" -> "pi pi-envelope";
            case "building" -> "pi pi-building";
            case "check", "ok" -> "pi pi-check";
            case "history", "time" -> "pi pi-history";
            default -> "pi pi-circle";
        };
    }

    // Helpers

    /** Construit l'URL avec le contextPath et force le préfixe /ui pour les routes internes. */
    private String resolveUrl(String raw) {
        if (raw == null) return "#";
        String url = raw.trim();
        if (url.isEmpty() || "#".equals(url)) return "#";

        String lower = url.toLowerCase(Locale.ROOT);
        // Liens externes : on ne touche pas
        if (lower.startsWith("http://") || lower.startsWith("https://")
                || lower.startsWith("mailto:") || lower.startsWith("tel:")) {
            return url;
        }

        // Interne : /ui + contextPath
        if (!url.startsWith("/")) url = "/" + url;         // "module" -> "/module"
        if (!url.startsWith("/ui/") && !url.equals("/ui")) // pas de doublon
            url = "/ui" + url;                             // "/module" -> "/ui/module"

        return ctx() + url;                                // "/app/ui/module"
    }

    private static String def(String s, String d) {
        return (s == null || s.isBlank()) ? d : s.trim();
    }

    private String ctx() {
        return FacesContext.getCurrentInstance()
                .getExternalContext().getRequestContextPath();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<","&lt;")
                .replace(">","&gt;")
                .replace("\"","&quot;");
    }

    // Option : MenuModel PrimeFaces

    private void buildPrimefacesModel() {
        DefaultMenuModel model = new DefaultMenuModel();

        // Accueil
        DefaultSubMenu home = DefaultSubMenu.builder().label("ACCUEIL").icon("pi pi-home").build();
        home.getElements().add(DefaultMenuItem.builder()
                .value("Tableau de bord")
                .icon("pi pi-home")
                .url(ctx() + "/ui/home")
                .build());
        model.getElements().add(home);

        // Modules
        for (ModuleP mod : modules) {
            DefaultSubMenu sm = DefaultSubMenu.builder()
                    .label(mod.getNom())
                    .icon("pi pi-folder")
                    .build();
            List<MenuNode> roots = treeByModule.get(mod.getId());
            if (roots != null) for (MenuNode n : roots) addNodePF(sm, n);
            model.getElements().add(sm);
        }
        this.pfModel = model;
    }

    private void addNodePF(DefaultSubMenu parent, MenuNode node) {
        Menu m = node.getData();
        if (!node.hasChildren()) {
            parent.getElements().add(DefaultMenuItem.builder()
                    .value(m.getNom())
                    .icon("pi pi-angle-right")
                    .url(resolveUrl(m.getUrl()))
                    .build());
        } else {
            DefaultSubMenu sub = DefaultSubMenu.builder()
                    .label(m.getNom())
                    .icon("pi pi-folder")
                    .build();
            for (MenuNode c : node.getChildren()) addNodePF(sub, c);
            parent.getElements().add(sub);
        }
    }
}
