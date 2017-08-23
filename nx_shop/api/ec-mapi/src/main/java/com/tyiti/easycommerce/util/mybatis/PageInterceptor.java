package com.tyiti.easycommerce.util.mybatis;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;

import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.factory.DefaultObjectFactory;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.wrapper.DefaultObjectWrapperFactory;
import org.apache.ibatis.reflection.wrapper.ObjectWrapperFactory;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.log4j.Logger;

/**
 * 自动分页
 * @author rainyhao
 * @since 2015-5-26 下午3:38:58
 */
@Intercepts({@Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class})})
public class PageInterceptor implements Interceptor {

	private final Logger logger = Logger.getLogger(PageInterceptor.class);
	private static final ObjectFactory DEFAULT_OBJECT_FACTORY = new DefaultObjectFactory();
	private static final ObjectWrapperFactory DEFAULT_OBJECT_WRAPPER_FACTORY = new DefaultObjectWrapperFactory();
	private String dialect = ""; // 数据库类型(默认为mysql)
	private String pagerMethodPattern = ""; // 需要拦截的ID(正则匹配)

	public Object intercept(Invocation invocation) throws Throwable {
		StatementHandler statementHandler = (StatementHandler) invocation.getTarget();
		MetaObject metaStatementHandler = MetaObject.forObject(statementHandler, DEFAULT_OBJECT_FACTORY, DEFAULT_OBJECT_WRAPPER_FACTORY);
		// 分离代理对象链(由于目标类可能被多个拦截器拦截，从而形成多次代理，通过下面的两次循环可以分离出最原始的的目标类)
		while (metaStatementHandler.hasGetter("h")) {
			Object object = metaStatementHandler.getValue("h");
			metaStatementHandler = MetaObject.forObject(object, DEFAULT_OBJECT_FACTORY, DEFAULT_OBJECT_WRAPPER_FACTORY);
		}
		// 分离最后一个代理对象的目标类
		while (metaStatementHandler.hasGetter("target")) {
			Object object = metaStatementHandler.getValue("target");
			metaStatementHandler = MetaObject.forObject(object, DEFAULT_OBJECT_FACTORY, DEFAULT_OBJECT_WRAPPER_FACTORY);
		}
		checkPropertiesSafe();
		MappedStatement mappedStatement = (MappedStatement) metaStatementHandler.getValue("delegate.mappedStatement");
		// 只重写需要分页的sql语句。通过MappedStatement的ID匹配，默认重写以Page结尾的MappedStatement的sql
		if (mappedStatement.getId().matches(pagerMethodPattern)) {
			BoundSql boundSql = (BoundSql) metaStatementHandler.getValue("delegate.boundSql");
			Object parameterObject = boundSql.getParameterObject();
			if (parameterObject == null) {
				throw new NullPointerException("parameterObject is null!");
			} else {
				Pager page = (Pager) metaStatementHandler.getValue("delegate.boundSql.parameterObject.pager");
				if (null == page) {
					throw new MyBatisException("查询对象中page对象为空, 请设置分页参数");
				}
				String sql = boundSql.getSql();
				// 重写sql
				String pageSql = buildPageSql(sql, page);
				metaStatementHandler.setValue("delegate.boundSql.sql", pageSql);
				// 采用物理分页后，就不需要mybatis的内存分页了，所以重置下面的两个参数
				metaStatementHandler.setValue("delegate.rowBounds.offset", RowBounds.NO_ROW_OFFSET);
				metaStatementHandler.setValue("delegate.rowBounds.limit", RowBounds.NO_ROW_LIMIT);
				if (page.getDoCount()) {
					Connection connection = (Connection) invocation.getArgs()[0];
					// 重设分页参数里的总页数等
					setPager(sql, connection, mappedStatement, boundSql, page);
				}
			}
		}
		// 将执行权交给下一个拦截器
		return invocation.proceed();
	}
	
	public Object plugin(Object target) {
		// 当目标类是StatementHandler类型时，才包装目标类，否者直接返回目标本身,减少目标被代理的次数
		if (target instanceof StatementHandler) {
			return Plugin.wrap(target, this);
		} else {
			return target;
		}
	}

	// 读拦截器的属性
	public void setProperties(Properties properties) {
		dialect = properties.getProperty("dialect");
		pagerMethodPattern = properties.getProperty("pagerMethodPattern");
		checkPropertiesSafe();
	}
	
	// 检查必填属性是否配置了值
	private void checkPropertiesSafe() {
		if (null == dialect || "".equals(dialect)) {
			throw new MyBatisException("未指定分页属性: dialect, 请在mybatis配置文件中定义property并且名称为dialect");
		}
		if (null == pagerMethodPattern || "".equals(pagerMethodPattern)) {
			throw new MyBatisException("未指定分页属性: pagerMethodPattern, 请在mybatis配置文件中定义property并且名称为pagerMethodPattern");
		}
	}
	
	/**
	 * 从数据库里查询总的记录数并计算总页数，回写进分页参数<code>PageInfo</code>,这样调用者就可用通过 分页参数
	 * <code>PageInfo</code>获得相关信息。
	 * @param sql
	 * @param connection
	 * @param mappedStatement
	 * @param boundSql
	 * @param page
	 */
	private void setPager(String sql, Connection connection, MappedStatement mappedStatement, BoundSql boundSql, Pager page) {
		// 记录总记录数
		String countSql = "select count(0) " + sql.substring(sql.toLowerCase().indexOf("from"));
		PreparedStatement countStmt = null;
		ResultSet rs = null;
		try {
			countStmt = connection.prepareStatement(countSql);
			BoundSql countBS = new BoundSql(mappedStatement.getConfiguration(), countSql, boundSql.getParameterMappings(), boundSql.getParameterObject());
			setParameters(countStmt, mappedStatement, countBS, boundSql.getParameterObject());
			rs = countStmt.executeQuery();
			int totalCount = 0;
			if (rs.next()) {
				totalCount = rs.getInt(1);
			}
			page.setTotal(totalCount);
		} catch (SQLException e) {
			logger.error("Ignore this exception", e);
		} finally {
			try {
				rs.close();
			} catch (SQLException e) {
				logger.error("Ignore this exception", e);
			}
			try {
				countStmt.close();
			} catch (SQLException e) {
				logger.error("Ignore this exception", e);
			}
		}

	}

	/**
	 * 对SQL参数(?)设值
	 * @param ps
	 * @param mappedStatement
	 * @param boundSql
	 * @param parameterObject
	 * @throws SQLException
	 */
	private void setParameters(PreparedStatement ps, MappedStatement mappedStatement, BoundSql boundSql, Object parameterObject) throws SQLException {
		ParameterHandler parameterHandler = new DefaultParameterHandler(mappedStatement, parameterObject, boundSql);
		parameterHandler.setParameters(ps);
	}

	/**
	 * 根据数据库类型，生成特定的分页sql
	 * @param sql
	 * @param page
	 * @return
	 */
	private String buildPageSql(String sql, Pager page) {
		if (page != null) {
			StringBuilder pageSql = new StringBuilder();
			if ("mysql".equals(dialect)) {
				pageSql = buildPageSqlForMysql(sql, page);
			} else if ("oracle".equals(dialect)) {
				pageSql = buildPageSqlForOracle(sql, page);
			} else {
				return sql;
			}
			return pageSql.toString();
		} else {
			return sql;
		}
	}

	/**
	 * mysql的分页语句
	 * @param sql
	 * @param page
	 * @return String
	 */
	public StringBuilder buildPageSqlForMysql(String sql, Pager page) {
		StringBuilder pageSql = new StringBuilder(100);
		String beginrow = String.valueOf((page.getPage() - 1) * page.getRows());
		pageSql.append(sql);
		pageSql.append(" limit " + beginrow + "," + page.getRows());
		return pageSql;
	}

	/**
	 * 参考hibernate的实现完成oracle的分页
	 * @param sql
	 * @param page
	 * @return String
	 */
	public StringBuilder buildPageSqlForOracle(String sql, Pager page) {
		StringBuilder pageSql = new StringBuilder(100);
		String beginrow = String.valueOf((page.getPage() - 1) * page.getRows());
		String endrow = String.valueOf(page.getPage() * page.getRows());
		pageSql.append("select * from ( select temp.*, rownum row_id from ( ");
		pageSql.append(sql);
		pageSql.append(" ) temp where rownum <= ").append(endrow);
		pageSql.append(") where row_id > ").append(beginrow);
		return pageSql;
	}

}