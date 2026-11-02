package cd.mura.hrx.service;

import cd.mura.hrx.dao.config.AppDAO;
import cd.mura.hrx.entity.config.App;
import cd.mura.hrx.entity.config.Users;
import cd.mura.hrx.utils.LdapUrl;
import jakarta.ejb.EJB;
import jakarta.ejb.Stateless;
import lombok.extern.slf4j.Slf4j;

import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.directory.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Hashtable;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Contrôle non bloquant de l'état d'un compte dans un annuaire LDAP/Active Directory. */
@Stateless @Slf4j
public class LdapAccountStatusService {
    private static final Duration CACHE_DURATION=Duration.ofMinutes(10);
    private static final int ACCOUNT_DISABLED=0x0002;
    private static final int ACCOUNT_LOCKED=0x0010;
    private final Map<Integer,CachedStatus> cache=new ConcurrentHashMap<>();
    @EJB private AppDAO appDAO;

    public AccountStatus check(Users user){return check(user,false);}
    public AccountStatus check(Users user,boolean force){
        if(user==null||user.getId()<=0)return new AccountStatus(State.NOT_FOUND,"Utilisateur introuvable");
        CachedStatus cached=cache.get(user.getId());
        if(!force&&cached!=null&&cached.checkedAt().plus(CACHE_DURATION).isAfter(Instant.now()))return cached.status();
        AccountStatus status=query(user);cache.put(user.getId(),new CachedStatus(status,Instant.now()));
        if(!status.assignable())log.warn("Compte {} non disponible pour affectation: {}",user.getLogin(),status.detail());
        return status;
    }

    private AccountStatus query(Users user){
        App app=appDAO.findFirst();
        if(app==null||blank(app.getLdapServerName())||blank(app.getLdapPort())||blank(app.getLdapBaseDn())||blank(app.getLdapBindUser())||blank(app.getLdapBindPassword()))
            return new AccountStatus(State.NOT_CONFIGURED,"Contrôle de l’annuaire indisponible : compte de lecture ou base de recherche non configuré");
        String url = LdapUrl.of(app.getLdapServerName(), app.getLdapPort());
        Hashtable<String,String> env=new Hashtable<>();env.put(Context.INITIAL_CONTEXT_FACTORY,"com.sun.jndi.ldap.LdapCtxFactory");env.put(Context.PROVIDER_URL,url);
        env.put(Context.SECURITY_AUTHENTICATION,"simple");env.put(Context.SECURITY_PRINCIPAL,app.getLdapBindUser().trim());env.put(Context.SECURITY_CREDENTIALS,app.getLdapBindPassword());
        env.put("com.sun.jndi.ldap.connect.timeout","5000");env.put("com.sun.jndi.ldap.read.timeout","5000");
        DirContext context=null;
        try{
            context=new InitialDirContext(env);SearchControls controls=new SearchControls();controls.setSearchScope(SearchControls.SUBTREE_SCOPE);controls.setCountLimit(2);
            controls.setReturningAttributes(new String[]{"userAccountControl","msDS-User-Account-Control-Computed","accountExpires","nsAccountLock","employeeStatus"});
            String login=ldapEscape(user.getLogin()),email=ldapEscape(user.getEmail());
            String filter="(&(objectClass=person)(|(sAMAccountName="+login+")(userPrincipalName="+login+")(mail="+email+")))";
            NamingEnumeration<SearchResult> results=context.search(app.getLdapBaseDn().trim(),filter,controls);
            if(!results.hasMore())return new AccountStatus(State.NOT_FOUND,"Compte absent de l’annuaire");
            return interpret(results.next().getAttributes(),Instant.now());
        }catch(Exception e){log.warn("Contrôle LDAP impossible pour {}: {}",user.getLogin(),e.getMessage());return new AccountStatus(State.UNAVAILABLE,"Annuaire injoignable : "+safeMessage(e));}
        finally{if(context!=null)try{context.close();}catch(Exception ignored){}}
    }

    static AccountStatus interpret(Attributes attributes,Instant now)throws Exception{
        long uac=number(attributes,"userAccountControl"),computed=number(attributes,"msDS-User-Account-Control-Computed");
        if((uac&ACCOUNT_DISABLED)!=0)return new AccountStatus(State.DISABLED,"Compte désactivé dans l’annuaire");
        if((computed&ACCOUNT_LOCKED)!=0)return new AccountStatus(State.DISABLED,"Compte verrouillé dans l’annuaire");
        String lock=text(attributes,"nsAccountLock");if("true".equalsIgnoreCase(lock))return new AccountStatus(State.DISABLED,"Compte verrouillé dans l’annuaire");
        String employee=text(attributes,"employeeStatus");if(employee!=null&&java.util.Set.of("inactive","disabled","terminated","suspended").contains(employee.toLowerCase(Locale.ROOT)))return new AccountStatus(State.DISABLED,"Statut dans l’annuaire : "+employee);
        long expires=number(attributes,"accountExpires");
        if(expires>0&&expires!=Long.MAX_VALUE){long epochMillis=(expires/10_000L)-11_644_473_600_000L;if(epochMillis<now.toEpochMilli())return new AccountStatus(State.DISABLED,"Compte de l’annuaire arrivé à expiration");}
        return new AccountStatus(State.ACTIVE,"Compte actif dans l’annuaire");
    }
    private static long number(Attributes a,String name)throws Exception{Attribute v=a.get(name);if(v==null||v.get()==null)return 0;try{return Long.parseLong(v.get().toString());}catch(NumberFormatException e){return 0;}}
    private static String text(Attributes a,String name)throws Exception{Attribute v=a.get(name);return v==null||v.get()==null?null:v.get().toString();}
    private static String ldapEscape(String value){if(value==null)return "";return value.replace("\\","\\5c").replace("*","\\2a").replace("(","\\28").replace(")","\\29").replace("\0","\\00");}
    private static boolean blank(String v){return v==null||v.isBlank();}
    private static String safeMessage(Exception e){String v=e.getMessage();return v==null||v.isBlank()?e.getClass().getSimpleName():v;}

    public enum State{ACTIVE,DISABLED,NOT_FOUND,UNAVAILABLE,NOT_CONFIGURED}
    public record AccountStatus(State state,String detail){
        public boolean assignable(){return state!=State.DISABLED&&state!=State.NOT_FOUND;}
        public boolean verified(){return state==State.ACTIVE||state==State.DISABLED||state==State.NOT_FOUND;}
    }
    private record CachedStatus(AccountStatus status,Instant checkedAt){}
}
