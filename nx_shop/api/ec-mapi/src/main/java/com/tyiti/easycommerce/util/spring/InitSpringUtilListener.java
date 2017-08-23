package com.tyiti.easycommerce.util.spring;

import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * 
* @ClassName: InitSpringUtilListener
* @Description: TODO(srping 加载上下文)
* @author Yan Zuoyu
* @date 2016-3-25 下午4:00:41
*
 */
public class InitSpringUtilListener implements ServletContextListener {
    private Log log = LogFactory.getLog(InitSpringUtilListener.class);

    /**
     * 构造
     */
    public InitSpringUtilListener() {
        // TODO Auto-generated constructor stub
    }

    /**
     * 销毁上下文
     * 
     * @param evt
     *            servlet上下文事件对象
     */
    public void contextDestroyed(ServletContextEvent evt) {

    }

    /**
     * 初始化上下文
     * 
     * @param evt
     *            servlet上下文事件对象
     */
    public void contextInitialized(ServletContextEvent evt) {
        log.info("开始初始化SpringUtil...");
        ServletContext ctx = evt.getServletContext();
        SpringUtil.init(ctx);
        String webRootAbsPath = evt.getServletContext().getRealPath("/");
        log.info("web root abs path=" + webRootAbsPath);
        SpringUtil.setWebRootAbsPath(webRootAbsPath);
        log.info("SpringUtil初始化完成.");
        
        //加载系统设置
 
    }

}
