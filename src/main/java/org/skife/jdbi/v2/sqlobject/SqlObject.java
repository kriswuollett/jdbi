package org.skife.jdbi.v2.sqlobject;

import com.fasterxml.classmate.MemberResolver;
import com.fasterxml.classmate.ResolvedType;
import com.fasterxml.classmate.ResolvedTypeWithMembers;
import com.fasterxml.classmate.TypeResolver;
import com.fasterxml.classmate.members.ResolvedMethod;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

class SqlObject implements InvocationHandler
{
    private static final TypeResolver                               typeResolver  = new TypeResolver();
    private static final Map<Method, Handler>                       mixinHandlers = new HashMap<Method, Handler>();
    private static final ConcurrentMap<Class, Map<Method, Handler>> handlersCache =
        new ConcurrentHashMap<Class, Map<Method, Handler>>();

    static {
        mixinHandlers.putAll(Transactional.Helper.handlers());
        mixinHandlers.putAll(GetHandle.Helper.handlers());
        mixinHandlers.putAll(CloseInternal.Helper.handlers());
    }

    static <T> T buildSqlObject(final Class<T> sqlObjectType, final HandleDing handle)
    {
        return (T) Proxy.newProxyInstance(sqlObjectType.getClassLoader(),
                                          new Class[]{sqlObjectType, CloseInternal.class},
                                          new SqlObject(buildHandlersFor(sqlObjectType), handle));
    }

    private static Map<Method, Handler> buildHandlersFor(Class sqlObjectType)
    {
        if (handlersCache.containsKey(sqlObjectType)) {
            return handlersCache.get(sqlObjectType);
        }

        final MemberResolver mr = new MemberResolver(typeResolver);
        final ResolvedType sql_object_type = typeResolver.resolve(sqlObjectType);

        final ResolvedTypeWithMembers d = mr.resolve(sql_object_type, null, null);

        final Map<Method, Handler> handlers = new HashMap<Method, Handler>();
        for (final ResolvedMethod method : d.getMemberMethods()) {
            final Method raw_method = method.getRawMember();
            final ResolvedType return_type = method.getReturnType();

            if (raw_method.isAnnotationPresent(SqlQuery.class)) {
                if (return_type.isInstanceOf(org.skife.jdbi.v2.Query.class)) {
                    handlers.put(raw_method, new QueryQueryHandler(method));
                }
                else if (return_type.isInstanceOf(List.class)) {
                    handlers.put(raw_method, new ListQueryHandler(method));
                }
                else if (return_type.isInstanceOf(Iterator.class)) {
                    handlers.put(raw_method, new IteratorQueryHandler(method));
                }
                else {
                    handlers.put(raw_method, new SingleValueQueryHandler(method));
                }
            }
            else if (raw_method.isAnnotationPresent(SqlUpdate.class)) {
                handlers.put(raw_method, new UpdateHandler(method));
            }
            else if (method.getName().equals("close") && method.getRawMember().getParameterTypes().length == 0) {
                handlers.put(raw_method, new CloseHandler());
            }
            else if (mixinHandlers.containsKey(raw_method)) {
                handlers.put(raw_method, mixinHandlers.get(raw_method));
            }
            else {
                throw new UnsupportedOperationException("Not Yet Implemented!");
            }

        }

        handlersCache.putIfAbsent(sqlObjectType, handlers);
        return handlers;
    }


    private final Map<Method, Handler> handlers;
    private final HandleDing           ding;

    public SqlObject(Map<Method, Handler> handlers, HandleDing ding)
    {
        this.handlers = handlers;
        this.ding = ding;
    }

    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable
    {
        try {
            ding.retain("top-level");
            return handlers.get(method).invoke(ding, proxy, args);
        }
        finally {
            ding.release("top-level");
        }
    }

    public static void close(Object sqlObject)
    {
        if (! (sqlObject instanceof CloseInternal)) {
            throw new IllegalArgumentException(sqlObject + " is not a sql object");
        }
        CloseInternal closer = (CloseInternal) sqlObject;
        closer.___jdbi_close___();
    }
}