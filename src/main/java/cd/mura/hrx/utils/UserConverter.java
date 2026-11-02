package cd.mura.hrx.utils;

import cd.mura.hrx.dao.config.UserDAO;
import cd.mura.hrx.entity.config.Users;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.component.UIComponent;
import jakarta.faces.context.FacesContext;
import jakarta.faces.convert.Converter;
import jakarta.faces.convert.FacesConverter;
import jakarta.inject.Inject;

@ApplicationScoped
@FacesConverter(value = "userConverter", managed = true)
public class UserConverter implements Converter<Users> {

    @Inject
    private UserDAO userDAO;

    @Override
    public String getAsString(FacesContext ctx, UIComponent comp, Users value) {
        if (value == null) return "";
        return String.valueOf(value.getId());
    }

    @Override
    public Users getAsObject(FacesContext ctx, UIComponent comp, String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return userDAO.findUserById(Integer.parseInt(value));
        } catch (Exception e) {
            return null;
        }
    }
}
