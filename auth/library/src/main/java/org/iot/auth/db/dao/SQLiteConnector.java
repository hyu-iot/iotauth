/*
 * Copyright (c) 2016, Regents of the University of California
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * IOTAUTH_COPYRIGHT_VERSION_1
 */

package org.iot.auth.db.dao;

import org.iot.auth.crypto.AuthCrypto;
import org.iot.auth.crypto.SymmetricKey;
import org.iot.auth.crypto.SymmetricKeyCryptoSpec;
import org.iot.auth.db.bean.*;
import org.iot.auth.exception.UseOfExpiredKeyException;
import org.iot.auth.io.Buffer;
import org.iot.auth.util.DateHelper;
import org.iot.auth.util.ExceptionToString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.security.*;
import java.security.cert.CertificateException;
import java.sql.*;
import java.util.*;
import java.util.Date;

/**
 * A SQLite connector Class for CRUD operations on Auth database.
 *
 * @author Salomon Lee, Hokeun Kim
 */
public class SQLiteConnector {
    public boolean DEBUG;
    private static final Logger logger = LoggerFactory.getLogger(SQLiteConnector.class);
    private Connection connection;
    private Statement statement;
    private String dbPath;
    private SymmetricKey databaseKey;
    // FIXME: should be set by properties
    public static final String AUTH_DB_KEY_ABSOLUTE_VALIDITY = "3650*day";
    public static final SymmetricKeyCryptoSpec AUTH_DB_CRYPTO_SPEC =
            new SymmetricKeyCryptoSpec("AES/CBC/PKCS5Padding", 16, "HmacSHA256");
    public static final String AUTH_DB_PUBLIC_CIPHER = "RSA/ECB/PKCS1PADDING";

    /**
     * Constructor that stores the physical location of the database file.
     * @param dbPath
     */
    public SQLiteConnector(String dbPath)
    {
        this.DEBUG = false;
        this.dbPath = dbPath;
    }

    /**
     * Load an encryption key for database.
     * @param databaseKeystorePath File path of Auth's database keystore.
     * @param authKeyStorePassword Password for Auth's database keystore
     * @throws CertificateException When CertificateException occurs.
     * @throws NoSuchAlgorithmException When NoSuchAlgorithmException occurs.
     * @throws KeyStoreException When KeyStoreException occurs.
     * @throws IOException When IOException occurs.
     * @throws SQLException When SQLException occurs.
     * @throws ClassNotFoundException When ClassNotFoundException occurs.
     * @throws UnrecoverableEntryException When UnrecoverableEntryException occurs.
     */
    public void initialize(String databaseKeystorePath, String authKeyStorePassword)
            throws CertificateException, NoSuchAlgorithmException, KeyStoreException, IOException, SQLException,
            ClassNotFoundException, UnrecoverableEntryException
    {
        KeyStore databaseKeyStore = AuthCrypto.loadKeyStore(databaseKeystorePath, authKeyStorePassword);
        if (databaseKeyStore.size() != 1) {
            throw new IllegalArgumentException("Auth key store must contain one key entry.");
        }
        Enumeration<String> aliases = databaseKeyStore.aliases();

        KeyStore.ProtectionParameter protParam =
                new KeyStore.PasswordProtection(authKeyStorePassword.toCharArray());
        PrivateKey databasePrivateKey = null;
        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            KeyStore.PrivateKeyEntry pkEntry = (KeyStore.PrivateKeyEntry) databaseKeyStore.getEntry(alias, protParam);
            logger.debug("Alias {}: , Cert: {}, Key: {}", alias, pkEntry.getCertificate(), pkEntry.getPrivateKey());
            databasePrivateKey = pkEntry.getPrivateKey();
        }
        String value = selectMetaDataValue(MetaDataTable.key.EncryptedDatabaseKey.name());
        Buffer encryptedDatabaseKey = Buffer.fromBase64(value);

        initialize(new SymmetricKey(
                AUTH_DB_CRYPTO_SPEC,
                new Date().getTime() + DateHelper.parseTimePeriod(AUTH_DB_KEY_ABSOLUTE_VALIDITY),
                AuthCrypto.privateDecrypt(encryptedDatabaseKey, databasePrivateKey, AUTH_DB_PUBLIC_CIPHER)));
    }

    public void initialize(SymmetricKey databaseKey) {
        this.databaseKey = databaseKey;
    }

    private Buffer encryptAuthDBData(Buffer input) {
        try {
            return databaseKey.encryptAuthenticate(input);
        } catch (UseOfExpiredKeyException e) {
            logger.error("UseOfExpiredKeyException {}", ExceptionToString.convertExceptionToStackTrace(e));
            throw new RuntimeException("Exception occurred while encrypting Auth DB Data!");
        }
    }
    private Buffer decryptAuthDBData(Buffer input) {
        try {
            return databaseKey.decryptVerify(input);
        }
        catch (Exception e) {
            logger.error("Exception {}", ExceptionToString.convertExceptionToStackTrace(e));
            throw new RuntimeException("Exception occurred while decrypting Auth DB Data!");
        }
    }

    private void setConnection() throws ClassNotFoundException, SQLException {
        Class.forName("org.sqlite.JDBC");
        if (connection == null || connection.isClosed()) {
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        }
    }

    /**
     * On cold start it will be needed to create a database and the related tables.
     * @throws SQLException if a database access error occurs;
     * this method is called on a closed <code>PreparedStatement</code>
     * or an argument is supplied to this method
     * @throws ClassNotFoundException if the class cannot be located
     */
    public void createTablesIfNotExists() throws SQLException, ClassNotFoundException {
        setConnection();
        String sql = "CREATE TABLE IF NOT EXISTS " + CommunicationPolicyTable.T_COMMUNICATION_POLICY + "(";
        sql += CommunicationPolicyTable.c.RequestingGroup.name() + " TEXT NOT NULL,";
        sql += CommunicationPolicyTable.c.TargetType.name() + " TEXT NOT NULL,";
        sql += CommunicationPolicyTable.c.Target.name() + " TEXT NOT NULL,";
        // MaxNumSessionKeyOwners should be greater than or equal to 2
        sql += CommunicationPolicyTable.c.MaxNumSessionKeyOwners.name() + " INT NOT NULL CHECK(" +
                CommunicationPolicyTable.c.MaxNumSessionKeyOwners.name() + " >= 2),";
        sql += CommunicationPolicyTable.c.SessionCryptoSpec.name() + " TEXT NOT NULL,";
        sql += CommunicationPolicyTable.c.AbsoluteValidity.name() + " TEXT NOT NULL,";
        sql += CommunicationPolicyTable.c.RelativeValidity.name() + " TEXT NOT NULL,";
        sql += "PRIMARY KEY (" + CommunicationPolicyTable.c.RequestingGroup.name() + ",";
        sql += CommunicationPolicyTable.c.TargetType.name() + ",";
        sql += CommunicationPolicyTable.c.Target.name() + "))";
        statement = connection.createStatement();
        if (DEBUG) logger.info(sql);
        if (statement.executeUpdate(sql) == 0)
            logger.info("Table {} created", CommunicationPolicyTable.T_COMMUNICATION_POLICY);
        else
            logger.info("Table {} already exists", CommunicationPolicyTable.T_COMMUNICATION_POLICY);
        closeStatement();

        statement = connection.createStatement();
        sql = "CREATE TABLE IF NOT EXISTS " + RegisteredEntityTable.T_REGISTERED_ENTITY + "(";
        sql += RegisteredEntityTable.c.Name.name() + " TEXT NOT NULL PRIMARY KEY,";
        sql += "'" + RegisteredEntityTable.c.Group.name() + "' TEXT NOT NULL,";
        sql += RegisteredEntityTable.c.DistProtocol.name() + " TEXT NOT NULL,";
        sql += RegisteredEntityTable.c.UsePermanentDistKey.name() + " BOOLEAN NOT NULL,";
        sql += RegisteredEntityTable.c.MaxSessionKeysPerRequest.name() + " INT NOT NULL,";
        sql += RegisteredEntityTable.c.PublKeyFile.name() + " TEXT,";
        sql += RegisteredEntityTable.c.DistKeyValidityPeriod.name() + " TEXT NOT NULL,";
        sql += RegisteredEntityTable.c.PublicKeyCryptoSpec.name() + " TEXT,";
        sql += RegisteredEntityTable.c.DistCryptoSpec.name() + " TEXT NOT NULL,";
        sql += RegisteredEntityTable.c.DistKeyExpirationTime.name() + " INT,";
        sql += RegisteredEntityTable.c.DistKeyVal.name() + " BLOB,";
        sql += RegisteredEntityTable.c.Active.name() + " BOOLEAN NOT NULL,";
        sql += RegisteredEntityTable.c.BackupToAuthID.name() + " INT,";
        sql += RegisteredEntityTable.c.BackupFromAuthID.name() + " INT)";
        if (DEBUG) logger.info(sql);
        if (statement.executeUpdate(sql) == 0)
            logger.info("Table {} created", RegisteredEntityTable.T_REGISTERED_ENTITY);
        else
            logger.info("Table {} already exists", RegisteredEntityTable.T_REGISTERED_ENTITY);
        closeStatement();

        statement = connection.createStatement();
        sql = "CREATE TABLE IF NOT EXISTS " + TrustedAuthTable.T_TRUSTED_AUTH + "(";
        sql += TrustedAuthTable.c.ID.name() + " INT NOT NULL PRIMARY KEY,";
        sql += TrustedAuthTable.c.Host.name() + " TEXT NOT NULL,";
        sql += TrustedAuthTable.c.Port.name() + " INT NOT NULL,";
        sql += TrustedAuthTable.c.CertificatePath.name() + " TEXT NOT NULL)";
        if (DEBUG) logger.info(sql);
        if (statement.executeUpdate(sql) == 0)
            logger.info("Table {} created", TrustedAuthTable.T_TRUSTED_AUTH);
        else
            logger.info("Table {} already exists", TrustedAuthTable.T_TRUSTED_AUTH);
        closeStatement();

        statement = connection.createStatement();
        sql = "CREATE TABLE IF NOT EXISTS " + CachedSessionKeyTable.T_CACHED_SESSION_KEY + "(";
        sql += CachedSessionKeyTable.c.ID.name() + " INT NOT NULL PRIMARY KEY,";
        sql += CachedSessionKeyTable.c.Owners.name() + " TEXT NOT NULL,";
        sql += CachedSessionKeyTable.c.MaxNumOwners.name() + " INT NOT NULL,";
        sql += CachedSessionKeyTable.c.Purpose.name() + " TEXT NOT NULL,";
        sql += CachedSessionKeyTable.c.ExpirationTime.name() + " INT NOT NULL,";
        sql += CachedSessionKeyTable.c.RelValidity.name() + " INT NOT NULL,";
        sql += CachedSessionKeyTable.c.CryptoSpec.name() + " TEXT NOT NULL,";
        sql += CachedSessionKeyTable.c.KeyVal.name() + " BLOB NOT NULL)";
        if (DEBUG) logger.info(sql);
        if (statement.executeUpdate(sql) == 0)
            logger.info("Table {} created", CachedSessionKeyTable.T_CACHED_SESSION_KEY);
        else
            logger.info("Table {} already exists", CachedSessionKeyTable.T_CACHED_SESSION_KEY);
        closeStatement();

        statement = connection.createStatement();
        sql = "CREATE TABLE IF NOT EXISTS " + MetaDataTable.T_META_DATA + "(";
        sql += MetaDataTable.c.Key.name() + " INT NOT NULL PRIMARY KEY,";
        sql += MetaDataTable.c.Value.name() + " TEXT NOT NULL)";
        if (DEBUG) logger.info(sql);
        if (statement.executeUpdate(sql) == 0)
            logger.info("Table {} created", MetaDataTable.T_META_DATA);
        else
            logger.info("Table {} already exists", MetaDataTable.T_META_DATA);
        closeStatement();

        closeConnection();
    }

    /**
     * Insert records into CommunicationPolicyTable.
     *
     * @param policy the records needed to set a communication policy.
     * @return <code>true</code> if the insertion has been successful
     *         <code>false</code> if the insertion has failed
     * @throws SQLException if a database access error occurs;
     * this method is called on a closed <code>PreparedStatement</code>
     * or an argument is supplied to this method
     * @throws ClassNotFoundException if the class cannot be located
     * @see CommunicationPolicyTable
     */
    public boolean insertRecords(CommunicationPolicyTable policy) throws SQLException, ClassNotFoundException {
        setConnection();
        String sql = "INSERT INTO " + CommunicationPolicyTable.T_COMMUNICATION_POLICY + "(";
        sql += CommunicationPolicyTable.c.RequestingGroup.name() + ",";
        sql += CommunicationPolicyTable.c.TargetType.name() + ",";
        sql += CommunicationPolicyTable.c.Target.name() + ",";
        sql += CommunicationPolicyTable.c.MaxNumSessionKeyOwners.name() + ",";
        sql += CommunicationPolicyTable.c.SessionCryptoSpec.name() + ",";
        sql += CommunicationPolicyTable.c.AbsoluteValidity.name() + ",";
        sql += CommunicationPolicyTable.c.RelativeValidity.name() + ")";
        sql += " VALUES (?,?,?,?,?,?,?)";
        int index = 1;
        PreparedStatement preparedStatement = connection.prepareStatement(sql);
        preparedStatement.setString(index++,policy.getReqGroup());
        preparedStatement.setString(index++,policy.getTargetTypeVal());
        preparedStatement.setString(index++,policy.getTarget());
        preparedStatement.setInt(index++,policy.getMaxNumSessionKeyOwners());
        preparedStatement.setString(index++,policy.getSessionCryptoSpec());
        preparedStatement.setString(index++,policy.getAbsValidityStr());
        preparedStatement.setString(index++,policy.getRelValidityStr());
        if (DEBUG) logger.info(preparedStatement.toString());
        boolean result = preparedStatement.execute();
        preparedStatement.close();
        closeConnection();
        return result;
    }

    public RegisteredEntityTable encryptRecords(RegisteredEntityTable regEntity) {
        if (regEntity.getDistKeyVal() != null) {
            regEntity.setDistKeyVal(encryptAuthDBData(new Buffer(regEntity.getDistKeyVal())).getRawBytes());
        }
        return regEntity;
    }
    public RegisteredEntityTable decryptRecords(RegisteredEntityTable regEntity) {
        if (regEntity.getDistKeyVal() != null) {
            regEntity.setDistKeyVal(decryptAuthDBData(new Buffer(regEntity.getDistKeyVal())).getRawBytes());
        }
        return regEntity;
    }

    /**
     * Insert records into RegistrationEntityTable
     *
     * @param regEntity the records registered as entity to be distributed among the clients.
     *
     * @return <code>true</code> if the insertion has been successful
     *         <code>false</code> if the insertion has failed
     * @throws SQLException  if a database access error occurs;
     * this method is called on a closed <code>PreparedStatement</code>
     * or an argument is supplied to this method
     * @throws ClassNotFoundException if the class cannot be located
     * @see RegisteredEntityTable
     */
    public boolean insertRecords(RegisteredEntityTable regEntity) throws SQLException, ClassNotFoundException {
        regEntity = encryptRecords(regEntity);
        setConnection();
        String sql = "INSERT INTO " + RegisteredEntityTable.T_REGISTERED_ENTITY + "(";
        sql += RegisteredEntityTable.c.Name.name() + ",";
        sql += "'"+ RegisteredEntityTable.c.Group.name() + "',";
        sql += RegisteredEntityTable.c.DistProtocol.name() + ",";
        sql += RegisteredEntityTable.c.UsePermanentDistKey.name() + ",";
        sql += RegisteredEntityTable.c.MaxSessionKeysPerRequest.name() + ",";
        sql += RegisteredEntityTable.c.PublicKeyCryptoSpec.name() + ",";
        sql += RegisteredEntityTable.c.PublKeyFile.name() + ",";
        sql += RegisteredEntityTable.c.DistKeyValidityPeriod.name() + ",";
        sql += RegisteredEntityTable.c.DistCryptoSpec.name() + ",";
        sql += RegisteredEntityTable.c.DistKeyExpirationTime.name() + ",";
        sql += RegisteredEntityTable.c.DistKeyVal.name() + ",";
        sql += RegisteredEntityTable.c.Active.name() + ",";
        sql += RegisteredEntityTable.c.BackupToAuthID.name() + ",";
        sql += RegisteredEntityTable.c.BackupFromAuthID.name() + ")";
        sql += " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        PreparedStatement preparedStatement = connection.prepareStatement(sql);
        int index = 1;
        preparedStatement.setString(index++,regEntity.getName());
        preparedStatement.setString(index++,regEntity.getGroup());
        preparedStatement.setString(index++,regEntity.getDistProtocol());
        preparedStatement.setBoolean(index++,regEntity.getUsePermanentDistKey());
        preparedStatement.setInt(index++,regEntity.getMaxSessionKeysPerRequest());
        preparedStatement.setString(index++,regEntity.getPublicKeyCryptoSpec());
        preparedStatement.setString(index++,regEntity.getPublicKeyFile());
        preparedStatement.setString(index++,regEntity.getDistKeyValidityPeriod());
        preparedStatement.setString(index++,regEntity.getDistCryptoSpec());
        byte[] distKeyVal = regEntity.getDistKeyVal();
        if (distKeyVal != null) {
            preparedStatement.setLong(index++, regEntity.getDistKeyExpirationTime());
            preparedStatement.setBytes(index++,distKeyVal);
        }
        else {
            preparedStatement.setNull(index++, Types.INTEGER);
            preparedStatement.setNull(index++, Types.BLOB);
        }

        preparedStatement.setBoolean(index++, regEntity.isActive());
        preparedStatement.setInt(index++, regEntity.getBackupToAuthID());
        preparedStatement.setInt(index++, regEntity.getBackupFromAuthID());

        preparedStatement.toString();
        if (DEBUG) logger.info("{}",preparedStatement);
        boolean result = preparedStatement.execute();
        preparedStatement.close();
        closeConnection();
        return result;
    }

    /**
     * Insert records related to the TrustedAuthTable
     *
     * @param auth the records registered as to be used as trusted authentication server
     * @return <code>true</code> if the insertion has been successful
     *         <code>false</code> if the insertion has failed
     * @throws SQLException  if a database access error occurs;
     * this method is called on a closed <code>PreparedStatement</code>
     * or an argument is supplied to this method
     * @throws ClassNotFoundException if the class cannot be located
     * @see TrustedAuthTable
     */
    public boolean insertRecords(TrustedAuthTable auth)  throws SQLException, ClassNotFoundException {
        setConnection();
        String sql = "INSERT INTO " + TrustedAuthTable.T_TRUSTED_AUTH + "(";
        sql += TrustedAuthTable.c.ID.name() + ",";
        sql += TrustedAuthTable.c.Host.name() + ",";
        sql += TrustedAuthTable.c.Port.name() + ",";
        sql += TrustedAuthTable.c.CertificatePath.name() + ")";
        sql += " VALUES(?,?,?,?)";
        PreparedStatement preparedStatement = connection.prepareStatement(sql);
        int index = 1;
        preparedStatement.setInt(index++,auth.getId());
        preparedStatement.setString(index++,auth.getHost());
        preparedStatement.setInt(index++,auth.getPort());
        preparedStatement.setString(index++,auth.getCertificatePath());
        if (DEBUG) logger.info("{}",preparedStatement);
        boolean result = preparedStatement.execute();
        preparedStatement.close();
        closeConnection();
        return result;
    }


    public CachedSessionKeyTable encryptRecords(CachedSessionKeyTable cachedSessionKey) {
        cachedSessionKey.setKeyVal(encryptAuthDBData(new Buffer(cachedSessionKey.getKeyVal())).getRawBytes());
        return cachedSessionKey;
    }
    public CachedSessionKeyTable decryptRecords(CachedSessionKeyTable cachedSessionKey) {
        cachedSessionKey.setKeyVal(decryptAuthDBData(new Buffer(cachedSessionKey.getKeyVal())).getRawBytes());
        return cachedSessionKey;
    }
    /**
     * Insert records related to the cached session keys
     *
     * @param cachedSessionKey the information of records related cached as session keys
     * @return <code>true</code> if the insertion has been successful
     *         <code>false</code> if the insertion has failed
     * @throws SQLException  if a database access error occurs;
     * this method is called on a closed <code>PreparedStatement</code>
     * or an argument is supplied to this method
     * @throws ClassNotFoundException if the class cannot be located
     * @see CachedSessionKeyTable
     */
    public boolean insertRecords(CachedSessionKeyTable cachedSessionKey) throws SQLException, ClassNotFoundException {
        encryptRecords(cachedSessionKey);
        setConnection();
        String sql = "INSERT INTO " + CachedSessionKeyTable.T_CACHED_SESSION_KEY + "(";
        sql += CachedSessionKeyTable.c.ID.name() + ",";
        sql += CachedSessionKeyTable.c.Owners.name() + ",";
        sql += CachedSessionKeyTable.c.MaxNumOwners.name() + ",";
        sql += CachedSessionKeyTable.c.Purpose.name() + ",";
        sql += CachedSessionKeyTable.c.ExpirationTime.name() + ",";
        sql += CachedSessionKeyTable.c.RelValidity.name() + ",";
        sql += CachedSessionKeyTable.c.CryptoSpec.name() + ",";
        sql += CachedSessionKeyTable.c.KeyVal.name() + ")";
        sql += " VALUES(?,?,?,?,?,?,?,?)";
        PreparedStatement preparedStatement = connection.prepareStatement(sql);
        int index = 1;
        preparedStatement.setLong(index++,cachedSessionKey.getID());
        preparedStatement.setString(index++,cachedSessionKey.getOwner());
        preparedStatement.setInt(index++,cachedSessionKey.getMaxNumOwners());
        preparedStatement.setString(index++,cachedSessionKey.getPurpose());
        preparedStatement.setLong(index++,cachedSessionKey.getAbsValidity());
        preparedStatement.setLong(index++,cachedSessionKey.getRelValidity());
        preparedStatement.setString(index++,cachedSessionKey.getSessionCryptoSpec());
        preparedStatement.setBytes(index++,cachedSessionKey.getKeyVal());
        if (DEBUG) logger.info("{}",preparedStatement);
        boolean result = preparedStatement.execute();
        preparedStatement.close();
        closeConnection();
        return result;
    }

    /**
     * Inserts the meta data information int the table meta data.
     *
     * @param metaData the object container of the information in meta data table
     * @return <code>true</code> if the insertion has been successful
     *         <code>false</code> if the insertion has failed
     * @throws SQLException  if a database access error occurs;
     * this method is called on a closed <code>PreparedStatement</code>
     * or an argument is supplied to this method
     * @throws ClassNotFoundException if the class cannot be located
     * @see MetaDataTable
     */
    public boolean insertRecords(MetaDataTable metaData) throws SQLException, ClassNotFoundException {
        setConnection();

        String sql = "INSERT INTO " + MetaDataTable.T_META_DATA + "(";
        sql += MetaDataTable.c.Key.name() + ",";
        sql += MetaDataTable.c.Value.name() + ")";
        sql += " VALUES(?,?)";
        PreparedStatement preparedStatement = connection.prepareStatement(sql);
        int index = 1;
        preparedStatement.setString(index++, metaData.getKey());
        preparedStatement.setString(index++, metaData.getValue());
        if (DEBUG) logger.info("{}",preparedStatement);
        boolean result = preparedStatement.execute();
        preparedStatement.close();
        closeConnection();
        return result;
    }

    /**
     * Selects all policies record from the table communication policy.
     * @return a list of all policies stored in the database
     * @throws SQLException  if a database access error occurs;
     * this method is called on a closed <code>PreparedStatement</code>
     * or an argument is supplied to this method
     * @throws ClassNotFoundException if the class cannot be located
     */
    public List<CommunicationPolicyTable> selectAllPolicies() throws SQLException, ClassNotFoundException {
        setConnection();
        statement = connection.createStatement();
        String sql = "SELECT * FROM " + CommunicationPolicyTable.T_COMMUNICATION_POLICY;
        if (DEBUG) logger.info(sql);
        ResultSet resultSet = statement.executeQuery(sql);
        List<CommunicationPolicyTable> policies = new LinkedList<>();
        while(resultSet.next()){
            CommunicationPolicyTable policy = CommunicationPolicyTable.createRecord(resultSet);
            policies.add(policy);
            if (DEBUG) logger.info(policy.toJSONObject().toJSONString());
        }
        closeStatement();
        closeConnection();
        return policies;
    }

    /**
     * Select all records stored in the table registered entity.
     * @param authDatabaseDir the path where the public key file is stored.
     * @return a list of all registered entities in the
     * @throws SQLException  if a database access error occurs;
     * this method is called on a closed <code>PreparedStatement</code>
     * or an argument is supplied to this method
     * @throws ClassNotFoundException if the class cannot be located
     */
    public List<RegisteredEntityTable> selectAllRegEntities(String authDatabaseDir) throws SQLException, ClassNotFoundException {
        setConnection();
        statement = connection.createStatement();
        String sql = "SELECT * FROM " + RegisteredEntityTable.T_REGISTERED_ENTITY;
        if (DEBUG) logger.info(sql);
        ResultSet resultSet = statement.executeQuery(sql);
        List<RegisteredEntityTable> entities = new LinkedList<>();
        while(resultSet.next()) {
            RegisteredEntityTable entity = RegisteredEntityTable.createRecord(authDatabaseDir, resultSet);
            entities.add(decryptRecords(entity));
            if (DEBUG) logger.info(entity.toJSONObject().toJSONString());
        }
        return entities;
    }

    /**
     * Updates the registered entity distribution key values.
     * @param regEntityName registered entity name
     * @param distKeyExpirationTime distribution key expiration time
     * @param distKeyVal the actual binary representation of the distribute key
     * @return <code>true</code> if the update success otherwise, <code>false</code>
     * @throws SQLException  if a database access error occurs;
     * this method is called on a closed <code>PreparedStatement</code>
     * or an argument is supplied to this method
     * @throws ClassNotFoundException if the class cannot be located
     */
    public boolean updateRegEntityDistKey(String regEntityName, long distKeyExpirationTime, Buffer distKeyVal)
            throws SQLException, ClassNotFoundException
    {
        distKeyVal = encryptAuthDBData(distKeyVal);
        setConnection();
        String sql = "UPDATE " + RegisteredEntityTable.T_REGISTERED_ENTITY;
        sql += " SET " + RegisteredEntityTable.c.DistKeyExpirationTime.name() + " = " + distKeyExpirationTime;
        sql += ", " + RegisteredEntityTable.c.DistKeyVal.name() + " = :DistKeyVal";
        sql += " WHERE " + RegisteredEntityTable.c.Name.name() + " = '" + regEntityName + "'";
        if (DEBUG) logger.info(sql);
        PreparedStatement preparedStatement  = connection.prepareStatement(sql);
        preparedStatement.setBytes(1, distKeyVal.getRawBytes());
        boolean result = preparedStatement.execute();
        // It's in auto-commit mode no need for explicit commit
        //_commit();
        return result;
    }

    /**
     * Selects all Trusted Auth records.
     *
     * @return a list of all trusted auth records.
     * @throws SQLException if a database access error occurs;
     * this method is called on a closed <code>PreparedStatement</code>
     * or an argument is supplied to this method
     * @throws ClassNotFoundException if the class cannot be located
     */
    public List<TrustedAuthTable> selectAllTrustedAuth() throws SQLException, ClassNotFoundException {
        setConnection();
        statement = connection.createStatement();
        String sql = "SELECT * FROM " + TrustedAuthTable.T_TRUSTED_AUTH;
        if (DEBUG) logger.info(sql);
        ResultSet resultSet = statement.executeQuery(sql);
        List<TrustedAuthTable> authList = new LinkedList<>();
        while (resultSet.next()) {
            TrustedAuthTable auth = TrustedAuthTable.createRecord(resultSet);
            if (DEBUG) logger.info(auth.toJSONObject().toJSONString());
            authList.add(auth);
        }
        return authList;
    }

    /**
     * Selects all cached session keys.
     *
     * @return a list of all cached session keys
     * @throws SQLException  if a database access error occurs;
     * this method is called on a closed <code>PreparedStatement</code>
     * or an argument is supplied to this method
     * @throws ClassNotFoundException if the class cannot be located
     */
    public List<CachedSessionKeyTable> selectAllCachedSessionKey() throws SQLException, ClassNotFoundException {
        setConnection();
        statement = connection.createStatement();
        String sql = "SELECT * FROM " + CachedSessionKeyTable.T_CACHED_SESSION_KEY;
        if (DEBUG) logger.info(sql);
        ResultSet resultSet = statement.executeQuery(sql);
        List<CachedSessionKeyTable> cachedSessionKeyList = new LinkedList<>();
        while (resultSet.next()) {
            CachedSessionKeyTable cachedSessionKey = CachedSessionKeyTable.createRecord(resultSet);
            if (DEBUG) logger.info(cachedSessionKey.toJSONObject().toJSONString());
            cachedSessionKeyList.add(decryptRecords(cachedSessionKey));
        }
        return cachedSessionKeyList;
    }

    /**
     * Select a specific cached key by its ID
     * @param id the id used to store this cached session key.
     * @return returns the Object container ${@link CachedSessionKeyTable}
     * @throws SQLException  if a database access error occurs;
     * this method is called on a closed <code>PreparedStatement</code>
     * or an argument is supplied to this method
     * @throws ClassNotFoundException if the class cannot be located
     */
    public CachedSessionKeyTable selectCachedSessionKeyByID(long id) throws SQLException, ClassNotFoundException {
        setConnection();
        statement = connection.createStatement();
        String sql = "SELECT * FROM " + CachedSessionKeyTable.T_CACHED_SESSION_KEY;
        sql += " WHERE " + CachedSessionKeyTable.c.ID.name() + " = " + id;
        if (DEBUG) logger.info(sql);
        ResultSet resultSet = statement.executeQuery(sql);
        CachedSessionKeyTable cachedSessionKey = null;
        while (resultSet.next()) {
            cachedSessionKey = CachedSessionKeyTable.createRecord(resultSet);
            if (DEBUG) logger.info(cachedSessionKey.toJSONObject().toJSONString());
        }
        return decryptRecords(cachedSessionKey);
    }

    /**
     * Select session keys with the same purpose and that is not expired yet.
     * @param purpose the given purpose of the session key
     * @return returns the list of session keys with the given session key
     * @throws SQLException if a database access error occurs;
     * this method is called on a closed <code>PreparedStatement</code>
     * or an argument is supplied to this method
     * @throws ClassNotFoundException if the class cannot be located
     */
    public List<CachedSessionKeyTable> selectCachedSessionKeysByPurpose(String requestingEntityName, String purpose)
            throws SQLException, ClassNotFoundException {
        setConnection();
        statement = connection.createStatement();
        String sql = "SELECT * FROM " + CachedSessionKeyTable.T_CACHED_SESSION_KEY;
        sql += " WHERE " + CachedSessionKeyTable.c.Purpose.name() + " = " + "'" + purpose + "'";
        sql += " AND " + CachedSessionKeyTable.c.Owners.name() + " NOT LIKE " + "'%" + requestingEntityName + "%'";
        long currentTime = new java.util.Date().getTime();
        sql += " AND " + CachedSessionKeyTable.c.ExpirationTime.name() + " > " + currentTime;
        if (DEBUG) logger.info(sql);
        ResultSet resultSet = statement.executeQuery(sql);
        List<CachedSessionKeyTable> result = new LinkedList<>();
        while (resultSet.next()) {
            CachedSessionKeyTable cachedSessionKey = CachedSessionKeyTable.createRecord(resultSet);
            if (DEBUG) logger.info(cachedSessionKey.toJSONObject().toJSONString());
            result.add(decryptRecords(cachedSessionKey));
        }
        return result;
    }

    /**
     * Deletes exprired cached session keys from the database.
     * @return <code>true</code> if the deletion is successful; otherwise, <code>false</code>
     * @throws SQLException  if a database access error occurs;
     * this method is called on a closed <code>PreparedStatement</code>
     * or an argument is supplied to this method
     * @throws ClassNotFoundException if the class cannot be located
     */
    public boolean deleteExpiredCahcedSessionKeys() throws SQLException, ClassNotFoundException {
        setConnection();
        String sql = "DELETE FROM " + CachedSessionKeyTable.T_CACHED_SESSION_KEY;
        long currentTime = new java.util.Date().getTime();
        sql += " WHERE " + CachedSessionKeyTable.c.ExpirationTime.name() + " < " + currentTime;
        if (DEBUG) logger.info(sql);
        PreparedStatement preparedStatement  = connection.prepareStatement(sql);
        boolean result = preparedStatement.execute();
        // It's in auto-commit mode no need for explicit commit
        //_commit();
        return result;
    }

    /**
     * Delete all cached session keys from the database.
     * @return <code>true</code> if the deletion is successful; otherwise, <code>false</code>
     * @throws SQLException if a database access error occurs;
     * @throws ClassNotFoundException if the class cannot be located
     */
    public boolean deleteAllCachedSessionKeys() throws SQLException, ClassNotFoundException {
        setConnection();
        String sql = "DELETE FROM " + CachedSessionKeyTable.T_CACHED_SESSION_KEY;
        if (DEBUG) logger.info(sql);
        PreparedStatement preparedStatement  = connection.prepareStatement(sql);
        return preparedStatement.execute();
    }

    /**
     * Append a owner to a session key.
     * @param keyID the id of the session key
     * @param newOwner the owner to the session key
     * @return <code>true</code> if the append is successful; otherwise, <code>false</code>
     * @throws SQLException  if a database access error occurs;
     * this method is called on a closed <code>PreparedStatement</code>
     * or an argument is supplied to this method
     * @throws ClassNotFoundException if the class cannot be located
     */
    public boolean appendSessionKeyOwner(long keyID, String newOwner) throws SQLException, ClassNotFoundException {
        setConnection();
        String sql = "UPDATE " + CachedSessionKeyTable.T_CACHED_SESSION_KEY;
        sql += " SET " + CachedSessionKeyTable.c.Owners.name() + " = ";
        sql += CachedSessionKeyTable.c.Owners.name() + "|| ',' || " + "'" + newOwner + "'";
        sql += " WHERE " + CachedSessionKeyTable.c.ID.name() + " = " + keyID;
        if (DEBUG) logger.info(sql);
        PreparedStatement preparedStatement  = connection.prepareStatement(sql);
        boolean result = preparedStatement.execute();
        // It's in auto-commit mode no need for explicit commit
        //_commit();
        return result;
    }

    /**
     * Select the value of a meta data by its key
     * @param key the key to be selected
     * @return the string representation of the metadata's value
     * @throws SQLException  if a database access error occurs;
     * this method is called on a closed <code>PreparedStatement</code>
     * or an argument is supplied to this method
     * @throws ClassNotFoundException if the class cannot be located
     */
    public String selectMetaDataValue(String key) throws SQLException, ClassNotFoundException {
        setConnection();
        statement = connection.createStatement();
        String sql = "SELECT * FROM " + MetaDataTable.T_META_DATA;
        sql += " WHERE " + MetaDataTable.c.Key.name() + " = '" + key + "'";
        if (DEBUG) logger.info(sql);
        ResultSet resultSet = statement.executeQuery(sql);
        MetaDataTable metaData = null;
        while (resultSet.next()) {
            metaData = MetaDataTable.createRecord(resultSet);
            if (DEBUG) logger.info(metaData.toJSONObject().toJSONString());
        }
        closeStatement();
        return metaData.getValue();
    }

    /**
     * Updates the metadata value on the given key
     * @param key the key value of the metadata to update
     * @param value the value to update
     * @return <code>true</code> if the update is successful; otherwise <code>false</code>
     * @throws SQLException  if a database access error occurs;
     * this method is called on a closed <code>PreparedStatement</code>
     * or an argument is supplied to this method
     * @throws ClassNotFoundException if the class cannot be located
     */
    public boolean updateMetaData(String key, String value) throws SQLException, ClassNotFoundException
    {
        setConnection();
        String sql = "UPDATE " + MetaDataTable.T_META_DATA;
        sql += " SET " + MetaDataTable.c.Value.name() + " = '" + value + "'";
        sql += " WHERE " + MetaDataTable.c.Key.name() + " = '" + key + "'";
        if (DEBUG) logger.info(sql);
        PreparedStatement preparedStatement  = connection.prepareStatement(sql);
        boolean result = preparedStatement.execute();
        // It's in auto-commit mode no need for explicit commit
        //_commit();
        preparedStatement.close();
        return result;

    }

    /**
     * Close the ${@link PreparedStatement}.
     * <pre>
     *     Recomended to be used after execute the sql through the ${@link Connection}
     * </pre>
     * @throws SQLException SQLException if a database access error occurs
     */
    public void closeStatement() throws SQLException {
        statement.close();
    }

    /**
     * Close the connection to the database.
     * @throws SQLException SQLException if a database access error occurs
     */
    public void closeConnection() throws SQLException {
        connection.close();
    }


}
