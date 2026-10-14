package com.billtracker.config;

import com.billtracker.user.AppUser;
import com.billtracker.user.UserService;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/** Lets controller methods declare an {@link AppUser} parameter for the signed-in user. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final UserService userService;

    public WebConfig(UserService userService) {
        this.userService = userService;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return parameter.getParameterType() == AppUser.class;
            }

            @Override
            public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav,
                                          NativeWebRequest request, WebDataBinderFactory binderFactory) {
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                if (auth == null || auth instanceof AnonymousAuthenticationToken) {
                    throw new IllegalStateException("No signed-in user for " + parameter.getMethod());
                }
                return userService.require(auth.getName());
            }
        });
    }
}
