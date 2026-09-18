package com.fivetech.framework.web.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import com.fivetech.common.constant.HttpStatus;
import com.fivetech.common.core.domain.AjaxResult;
import com.fivetech.common.core.text.Convert;
import com.fivetech.common.exception.DemoModeException;
import com.fivetech.common.exception.ServiceException;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.common.utils.TraceIdUtils;
import com.fivetech.common.utils.html.EscapeUtil;
import com.fivetech.common.utils.ip.IpUtils;
import com.fivetech.framework.alert.AlertContext;
import com.fivetech.framework.alert.AlertLevel;
import com.fivetech.framework.alert.ErrorAlertService;

/**
 * 全局异常处理器
 * 
 * @author fivetech
 */
@RestControllerAdvice
public class GlobalExceptionHandler
{
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 告警关闭或未装配时为 null，此处只记日志，不影响任何接口 */
    @Autowired(required = false)
    private ErrorAlertService errorAlertService;

    /**
     * 权限校验异常
     */
    @ExceptionHandler(AccessDeniedException.class)
    public AjaxResult handleAccessDeniedException(AccessDeniedException e, HttpServletRequest request)
    {
        String requestURI = request.getRequestURI();
        log.error("请求地址'{}',权限校验失败'{}'", requestURI, e.getMessage());
        return AjaxResult.error(HttpStatus.FORBIDDEN, "没有权限，请联系管理员授权");
    }

    /**
     * 请求方式不支持
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public AjaxResult handleHttpRequestMethodNotSupported(HttpRequestMethodNotSupportedException e,
            HttpServletRequest request)
    {
        String requestURI = request.getRequestURI();
        log.error("请求地址'{}',不支持'{}'请求", requestURI, e.getMethod());
        return AjaxResult.error(e.getMessage());
    }

    /**
     * 业务异常
     */
    @ExceptionHandler(ServiceException.class)
    public AjaxResult handleServiceException(ServiceException e, HttpServletRequest request)
    {
        log.error(e.getMessage(), e);
        Integer code = e.getCode();
        return StringUtils.isNotNull(code) ? AjaxResult.error(code, e.getMessage()) : AjaxResult.error(e.getMessage());
    }

    /**
     * 请求路径中缺少必需的路径变量
     */
    @ExceptionHandler(MissingPathVariableException.class)
    public AjaxResult handleMissingPathVariableException(MissingPathVariableException e, HttpServletRequest request)
    {
        String requestURI = request.getRequestURI();
        log.error("请求路径中缺少必需的路径变量'{}',发生系统异常.", requestURI, e);
        return AjaxResult.error(String.format("请求路径中缺少必需的路径变量[%s]", e.getVariableName()));
    }

    /**
     * 请求参数类型不匹配
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public AjaxResult handleMethodArgumentTypeMismatchException(MethodArgumentTypeMismatchException e, HttpServletRequest request)
    {
        String requestURI = request.getRequestURI();
        String value = Convert.toStr(e.getValue());
        if (StringUtils.isNotEmpty(value))
        {
            value = EscapeUtil.clean(value);
        }
        log.error("请求参数类型不匹配'{}',发生系统异常.", requestURI, e);
        return AjaxResult.error(String.format("请求参数类型不匹配，参数[%s]要求类型为：'%s'，但输入值为：'%s'", e.getName(), e.getRequiredType().getName(), value));
    }

    /**
     * 拦截未知的运行时异常
     */
    @ExceptionHandler(RuntimeException.class)
    public AjaxResult handleRuntimeException(RuntimeException e, HttpServletRequest request)
    {
        String requestURI = request.getRequestURI();
        log.error("请求地址'{}',发生未知异常.", requestURI, e);
        pushAlert("系统未捕获异常", e, request);
        return errorWithTrace(e.getMessage());
    }

    /**
     * 系统异常
     */
    @ExceptionHandler(Exception.class)
    public AjaxResult handleException(Exception e, HttpServletRequest request)
    {
        String requestURI = request.getRequestURI();
        log.error("请求地址'{}',发生系统异常.", requestURI, e);
        pushAlert("系统异常", e, request);
        return errorWithTrace(e.getMessage());
    }

    /**
     * 推送系统错误告警。
     * <p>
     * 只有未捕获异常走到这里，业务异常（ServiceException）不告警。
     * 组装过程本身不允许抛异常，否则会把一次普通报错变成 500 处理失败。
     */
    private void pushAlert(String title, Throwable e, HttpServletRequest request)
    {
        if (errorAlertService == null)
        {
            return;
        }
        try
        {
            errorAlertService.alert(AlertContext.of(AlertLevel.P2, title, e)
                .setTraceId(TraceIdUtils.get())
                .setMethod(request.getMethod())
                .setUri(request.getRequestURI())
                .setUsername(currentUsername())
                .setClientIp(IpUtils.getIpAddr(request)));
        }
        catch (Exception ignored)
        {
            // 告警组装失败不能影响异常响应本身
        }
    }

    /**
     * 安全获取当前用户名：未登录时 SecurityUtils 会抛异常，这里不能让它冒出去
     */
    private String currentUsername()
    {
        try
        {
            return com.fivetech.common.utils.SecurityUtils.getUsername();
        }
        catch (Exception e)
        {
            return "anonymous";
        }
    }

    /**
     * 构造带追踪号的错误响应。
     * <p>
     * 追踪号同时放在 traceId 字段与提示文案里：前者供前端上报，
     * 后者让用户截图反馈时就能带上，无需额外操作。
     */
    private AjaxResult errorWithTrace(String message)
    {
        String traceId = TraceIdUtils.get();
        String text = StringUtils.isEmpty(message) ? "系统异常，请联系管理员" : message;
        if (StringUtils.isNotEmpty(traceId))
        {
            text = text + "（追踪号 " + traceId + "）";
        }
        AjaxResult result = AjaxResult.error(text);
        result.put("traceId", traceId);
        return result;
    }

    /**
     * 自定义验证异常
     */
    @ExceptionHandler(BindException.class)
    public AjaxResult handleBindException(BindException e)
    {
        log.error(e.getMessage(), e);
        String message = e.getAllErrors().get(0).getDefaultMessage();
        return AjaxResult.error(message);
    }

    /**
     * 自定义验证异常
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Object handleMethodArgumentNotValidException(MethodArgumentNotValidException e)
    {
        log.error(e.getMessage(), e);
        String message = e.getBindingResult().getFieldError().getDefaultMessage();
        return AjaxResult.error(message);
    }

    /**
     * 演示模式异常
     */
    @ExceptionHandler(DemoModeException.class)
    public AjaxResult handleDemoModeException(DemoModeException e)
    {
        return AjaxResult.error("演示模式，不允许操作");
    }
}
