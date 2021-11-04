package ru.itmo.wp.web;

import freemarker.template.*;
import ru.itmo.wp.web.exception.NotFoundException;
import ru.itmo.wp.web.exception.RedirectException;
import ru.itmo.wp.web.page.IndexPage;
import ru.itmo.wp.web.page.NotFoundPage;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

public class FrontServlet extends HttpServlet {
    private static final String BASE_PACKAGE = FrontServlet.class.getPackage().getName() + ".page";
    private static final String DEFAULT_ACTION = "action";

    private Configuration sourceConfiguration;
    private Configuration targetConfiguration;

    private Configuration newFreemarkerConfiguration(String templateDirName, boolean debug)
            throws ServletException {
        File templateDir = new File(templateDirName);
        if (!templateDir.isDirectory()) {
            return null;
        }

        Configuration configuration = new Configuration(Configuration.VERSION_2_3_31);
        try {
            configuration.setDirectoryForTemplateLoading(templateDir);
        } catch (IOException e) {
            throw new ServletException("Can't create freemarker configuration [templateDir="
                    + templateDir + "]");
        }
        configuration.setDefaultEncoding(StandardCharsets.UTF_8.name());
        configuration.setTemplateExceptionHandler(debug ? TemplateExceptionHandler.HTML_DEBUG_HANDLER :
                TemplateExceptionHandler.RETHROW_HANDLER);
        configuration.setLogTemplateExceptions(false);
        configuration.setWrapUncheckedExceptions(true);

        return configuration;
    }

    @Override
    public void init() throws ServletException {
        sourceConfiguration = newFreemarkerConfiguration(
                getServletContext().getRealPath("/") + "../../src/main/webapp/WEB-INF/templates", true);
        targetConfiguration = newFreemarkerConfiguration(
                getServletContext().getRealPath("WEB-INF/templates"), false);
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        process(request, response);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        process(request, response);
    }

    private void process(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        Route route = Route.newRoute(request);
        try {
            process(route, request, response);
        } catch (NotFoundException e) {
            try {
                process(Route.newNotFoundRoute(), request, response);
            } catch (NotFoundException notFoundException) {
                throw new ServletException(notFoundException);
            }
        }
    }

    private void process(Route route, HttpServletRequest request, HttpServletResponse response)
            throws NotFoundException, ServletException, IOException {
        Class<?> pageClass;
        try {
            pageClass = Class.forName(route.getClassName());
        } catch (ClassNotFoundException e) {
            throw new NotFoundException();
        }

        Method method = null;
        for (Class<?> clazz = pageClass; method == null && clazz != null; clazz = clazz.getSuperclass()) {
            try {
                String methodName = route.getAction();
                boolean chosen = false;
                for (Method m : clazz.getDeclaredMethods()) {
                    if (m.getName().equals(methodName)
                            && (m.getReturnType().equals(void.class))
                            && (m.getParameterCount() <= 2)) {
                        int viewCount = 0;
                        int requestCount = 0;
                        Type[] allTypes = m.getGenericParameterTypes();
                        boolean hasOther = false;
                        for (Type type : allTypes) {
                            String s = type.getTypeName();
                            if (type instanceof ParameterizedType) {
                                s = ((ParameterizedType) type).getRawType().getTypeName();
                            }
                            if (s.equals(Map.class.getName())) {
                                viewCount += 1;
                            }
                            else if (s.equals(HttpServletRequest.class.getName())) {
                                requestCount += 1;
                            }
                            else {
                                hasOther = true;
                            }
                        }
                        if (viewCount > 1 || requestCount > 1 || hasOther) {
                            continue;
                        }

                        if (chosen) {
                            throw new ServletException("Multiple methods matched for " + methodName
                                    + " in class " + clazz.getName());
                        }
                        method = m;
                        chosen = true;
                    }
                }
                if (method == null) {
                    throw new NoSuchMethodException("No such method could be found");
                }
            } catch (NoSuchMethodException ignored) {
                // No operations.
            }
        }

        if (method == null) {
            throw new NotFoundException();
        }

        Object page;
        try {
            page = pageClass.newInstance();
        } catch (InstantiationException | IllegalAccessException e) {
            throw new ServletException("Can't create page [pageClass="
                    + pageClass + "]");
        }

        Map<String, Object> view = new HashMap<>();
        method.setAccessible(true);
        try {
            ArrayList<Object> args = new ArrayList<>();
            Type[] allTypes = method.getGenericParameterTypes();
            for (Type type : allTypes) {
                String s = type.getTypeName();
                if (type instanceof ParameterizedType) {
                    s = ((ParameterizedType) type).getRawType().getTypeName();
                }
                if (s.equals(Map.class.getName())) {
                    args.add(view);
                }
                if (s.equals(HttpServletRequest.class.getName())) {
                    args.add(request);
                }
            }
            if (args.size() != method.getParameterCount()) {
                throw new ServletException("Wrong number of arguments (" + args.size() + ") passed to action-method ["
                        + method.getName() + "]");
            } else {
                method.invoke(page, args.toArray());
            }
        } catch (IllegalAccessException e) {
            throw new ServletException("Can't invoke action method [pageClass="
                    + pageClass + ", method=" + method + "]");
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RedirectException) {
                RedirectException redirectException = (RedirectException) cause;
                response.sendRedirect(redirectException.getTarget());
                return;
            } else {
                throw new ServletException("Can't invoke action method [pageClass="
                        + pageClass + ", method=" + method + "]", cause);
            }
        }

        String requestLanguage = request.getParameter("lang");
        Template template = null;
        HttpSession session = request.getSession();

        try {
            String language = (requestLanguage != null) ? requestLanguage : (String) session.getAttribute("lang");
            if (language != null && language.substring(0, 2).matches("[a-z]{2}")) {
                session.setAttribute("lang", language);
                template = newTemplate(pageClass.getSimpleName() + ".ftlh" , new Locale(language));
            }
            if (template == null) {
                template = newTemplate(pageClass.getSimpleName() + ".ftlh");
            }
        } catch (ServletException e) {
            template = newTemplate(pageClass.getSimpleName() + ".ftlh"); // ??
        }
        response.setContentType("text/html");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        try {
            template.process(view, response.getWriter());
        } catch (TemplateException e) {
            if (sourceConfiguration == null) {
                throw new ServletException("Can't render template [pageClass="
                        + pageClass + ", action=" + method + "]", e);
            }
        }
    }

    private Template newTemplate(String templateName, Locale locale) throws ServletException {
        Template template = null;

        if (sourceConfiguration != null) {
            try {
                template = sourceConfiguration.getTemplate(templateName, locale);
            } catch (TemplateNotFoundException ignored) {
                // No operations.
            } catch (IOException e) {
                throw new ServletException("Can't load template [templateName=" + templateName + "]", e);
            }
        }

        if (template == null && targetConfiguration != null) {
            try {
                template = targetConfiguration.getTemplate(templateName, locale);
            } catch (TemplateNotFoundException ignored) {
                // No operations.
            } catch (IOException e) {
                throw new ServletException("Can't load template [templateName=" + templateName + "]", e);
            }
        }

        if (template == null) {
            throw new ServletException("Can't find template [templateName=" + templateName + "]");
        }

        return template;
    }

    private Template newTemplate(String templateName) throws ServletException {
        return newTemplate(templateName, new Locale("en"));
    }

    private static class Route {
        private final String className;
        private final String action;

        private Route(String className, String action) {
            this.className = className;
            this.action = action;
        }

        private String getClassName() {
            return className;
        }

        private String getAction() {
            return action;
        }

        private static Route newNotFoundRoute() {
            return new Route(NotFoundPage.class.getName(), DEFAULT_ACTION);
        }

        private static Route newIndexRoute() {
            return new Route(IndexPage.class.getName(), DEFAULT_ACTION);
        }

        private static Route newRoute(HttpServletRequest request) {
            String uri = request.getRequestURI();

            List<String> classNameParts = Arrays.stream(uri.split("/"))
                    .filter(part -> !part.isEmpty())
                    .collect(Collectors.toList());

            if (classNameParts.isEmpty()) {
                return newIndexRoute();
            }

            StringBuilder simpleClassName = new StringBuilder(classNameParts.get(classNameParts.size() - 1));
            int lastDotIndex = simpleClassName.lastIndexOf(".");
            simpleClassName.setCharAt(lastDotIndex + 1,
                    Character.toUpperCase(simpleClassName.charAt(lastDotIndex + 1)));
            classNameParts.set(classNameParts.size() - 1, simpleClassName.toString());

            String className = BASE_PACKAGE + "." + String.join(".", classNameParts) + "Page";

            String action = request.getParameter("action");
            if (action == null || action.isEmpty()) {
                action = DEFAULT_ACTION;
            }

            return new Route(className, action);
        }
    }
}