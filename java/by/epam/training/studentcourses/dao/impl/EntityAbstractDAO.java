package by.epam.training.studentcourses.dao.impl;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import by.epam.training.studentcourses.dao.EntityDAO;
import by.epam.training.studentcourses.dao.exception.DAOException;
import by.epam.training.studentcourses.dao.exception.DBErrorMessages;
import by.epam.training.studentcourses.dao.exception.InternalDAOException;
import by.epam.training.studentcourses.dao.exception.InvalidEntityException;
import by.epam.training.studentcourses.dao.exception.InvalidRequestException;
import by.epam.training.studentcourses.dao.impl.pool.ConnectionPool;
import by.epam.training.studentcourses.dao.impl.pool.ConnectionPoolFactory;
import by.epam.training.studentcourses.util.Filter;
import by.epam.training.studentcourses.util.Identifiable;
import by.epam.training.studentcourses.util.TableAttr;


public abstract class EntityAbstractDAO<T extends Identifiable> implements EntityDAO<T> {
	
	private String insertPrepStatement;
	private String deleteByIdPrepStatement;
	private TableAttr idAttr;
	private String tableName;
	private TableAttr[] tableAttributes;
	private ConnectionPool connectionPool;
	
	protected EntityAbstractDAO(String tableName, TableAttr[] tableAttributes, TableAttr idAttr) {
		this.tableName = tableName;
		this.tableAttributes = tableAttributes;
		this.idAttr = idAttr;
		insertPrepStatement = PrepStHelper.genInsertStatement(tableName, tableAttributes.length);
		deleteByIdPrepStatement = PrepStHelper.genDeleteByIdStatement(tableName, idAttr);
		this.connectionPool = ConnectionPoolFactory.getInstance();
	}

	@Override
	public void add(List<T> entityList) throws DAOException {
		if (entityList.isEmpty()) {
			return;
		}
		Connection conn = null;
		PreparedStatement ps = null;
		try {
			try {
				conn = connectionPool.getConnection();
				ps = conn.prepareStatement(insertPrepStatement);
				for (T entity : entityList) {
					if (!validateEntityForInsert(entity)) {
						throw new InvalidEntityException(entity);
					}
					entity.setId(null);
					fillPrepStatementWithResultSet(entity, ps, false);
					System.out.println(ps.toString()); //LOGGER : log ps.toString();
					ps.addBatch();
				}
				ps.executeBatch();
				conn.commit();
			} finally {
				if (conn != null) {
					connectionPool.releaseConnection(conn);
				}
				if (ps != null) {
					ps.close();
				}
			}
		} catch(SQLException e) {
			throw new InternalDAOException(e);
		}
	}

	@Override
	public List<T> getByFilter(Filter filter) throws DAOException {
		Connection conn = null;
		List<T> entityList = new ArrayList<T>();
		T entity;
		try {
			conn = connectionPool.getConnection();
			if (!validateFilter(filter, tableAttributes)) {
				throw new InvalidRequestException(
						DBErrorMessages.getFilterDoesntMatchTableMessage(tableName, filter));
			}
			PreparedStatement ps = conn.prepareStatement(
					PrepStHelper.genSelectByFilterStatement(tableName, filter));
			PrepStHelper.fill(ps, true, filter);
			ResultSet rs = ps.executeQuery();
			connectionPool.releaseConnection(conn);
			while (rs.next()) {
				entity = createEntityByResultSet(rs);
				entityList.add(entity);
			}
			return entityList;
		} catch (SQLException e) {
			throw new InternalDAOException(e);
		} finally {
			if (conn != null) {
				connectionPool.releaseConnection(conn);
			}
		}
	}

	@Override
	public void update(List<T> entityList) throws DAOException {
		if (entityList.isEmpty()) {
			return;
		}
		Connection conn = null;
		try {
			conn = connectionPool.getConnection();
			PreparedStatement ps = null;
			boolean[] nullAttributesStates;
			for (T entity : entityList) {
				if (entity.getId() == null) {
					conn.rollback();
					throw new InvalidEntityException(entity, 
							DBErrorMessages.getEntityDoesntContainIdMessage(entity));
				}
				nullAttributesStates = getNullAttributesStates(entity);
				int nullAttrCount = 0;
				for (int i = 0; i < nullAttributesStates.length; i ++) {
					if (nullAttributesStates[i] && !tableAttributes[i].getAttrName().equals(idAttr.getAttrName())) {
						nullAttrCount ++;
					}
				}
				if (nullAttrCount == tableAttributes.length - 1) {
					continue;
				}
				ps = conn.prepareStatement(PrepStHelper.genUpdateByIdStatement(
						tableName, tableAttributes, nullAttributesStates, idAttr));
				fillPrepStatementWithResultSet(entity, ps, true);
				ps.setInt(ps.getParameterMetaData().getParameterCount(), entity.getId());
				System.out.println(ps.toString());
				ps.addBatch();
				//LOGGER
			}
			if (ps != null) {
				ps.executeBatch();
				conn.commit();
			}
		} catch (SQLException e) {
			try { 
				if (conn != null) {
					conn.rollback();
				}
			} catch (SQLException e1) {
				//LOGGER
			}
			throw new InternalDAOException(e);
		} finally {
			if (conn != null) {
				connectionPool.releaseConnection(conn);
			}
		}

	}

	@Override @Deprecated(forRemoval = false)
	public void deleteCascade(List<T> entityList) throws DAOException {
		if (entityList.isEmpty()) {
			return;
		}
		Connection conn = null;
		PreparedStatement ps = null;
		try {
			try {
				conn = connectionPool.getConnection();
				ps = conn.prepareStatement(deleteByIdPrepStatement);
				for (T entity : entityList) {
					if (entity.getId() == null) {
						conn.rollback();
						throw new InvalidEntityException(entity, 
								DBErrorMessages.getEntityDoesntContainIdMessage(entity));
					}
					ps.setInt(1, entity.getId());
					System.out.println(ps.toString());
					ps.addBatch();
				}
				ps.executeBatch();
				conn.commit(); //IT WORKS WITHOUT COMMIT!??? how
				//LOGGER
			} finally {
				if (ps != null) {
					ps.close();
				}
				if (conn != null) {
					connectionPool.releaseConnection(conn);
				}
			}
		} catch (SQLException e) {
			throw new InternalDAOException(e);
		}
	}
	
	public boolean validateFilter(Filter filter, TableAttr[] allowableAttributes) {
		for (int i = 0; i < filter.size(); i ++) {
			for (int j = 0; j < allowableAttributes.length; j ++) {
				if (filter.getAttrName(i).equals(allowableAttributes[i].getAttrName())) {
					break;
				}
				if (j == allowableAttributes.length) {
					return false;
				}
			}
		}
		return true;
	}
	
	public abstract boolean validateEntityForInsert(T entity);
	
	public abstract void fillPrepStatementWithResultSet(T entity, PreparedStatement ps, boolean skipNull) throws SQLException;
	
	public abstract T createEntityByResultSet(ResultSet rs) throws SQLException, DAOException;
	
	public abstract boolean[] getNullAttributesStates(T entity);
	

}
