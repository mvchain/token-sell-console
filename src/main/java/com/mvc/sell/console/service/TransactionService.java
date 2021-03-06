package com.mvc.sell.console.service;

import com.github.pagehelper.PageInfo;
import com.mvc.sell.console.constants.CommonConstants;
import com.mvc.sell.console.constants.MessageConstants;
import com.mvc.sell.console.constants.RedisConstants;
import com.mvc.sell.console.pojo.bean.Account;
import com.mvc.sell.console.pojo.bean.Capital;
import com.mvc.sell.console.pojo.bean.Config;
import com.mvc.sell.console.pojo.bean.Transaction;
import com.mvc.sell.console.pojo.dto.TransactionDTO;
import com.mvc.sell.console.pojo.vo.TransactionVO;
import com.mvc.sell.console.service.ethernum.ContractService;
import com.mvc.sell.console.util.BeanUtil;
import com.mvc.sell.console.util.Web3jUtil;
import lombok.extern.log4j.Log4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.admin.Admin;
import org.web3j.protocol.admin.methods.response.NewAccountIdentifier;
import org.web3j.protocol.admin.methods.response.PersonalUnlockAccount;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthGetBalance;
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.tx.Contract;
import rx.Subscription;
import rx.functions.Action1;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

/**
 * TransactionService
 *
 * @author qiyichen
 * @create 2018/3/13 12:06
 */
@Service
@Log4j
public class TransactionService extends BaseService {

    @Autowired
    Web3j web3j;
    @Autowired
    Admin admin;
    @Autowired
    ProjectService projectService;
    @Autowired
    AccountService accountService;
    @Autowired
    ConfigService configService;
    @Value("${wallet.password}")
    String password;
    @Value("${wallet.user}")
    String defaultUser;
    @Value("${wallet.coldUser}")
    String coldUser;
    @Value("${wallet.eth}")
    BigDecimal ethLimit;
    @Autowired
    ContractService contractService;

    public final static BigInteger DEFAULT_GAS_PRICE = Contract.GAS_PRICE.divide(BigInteger.valueOf(5));
    public final static BigInteger DEFAULT_GAS_LIMIT = Contract.GAS_LIMIT.divide(BigInteger.valueOf(10));

    public PageInfo<TransactionVO> transaction(TransactionDTO transactionDTO) {
        transactionDTO.setOrderId(StringUtils.isEmpty(transactionDTO.getOrderId()) ? null : transactionDTO.getOrderId());
        Transaction transaction = (Transaction) BeanUtil.copyProperties(transactionDTO, new Transaction());
        List<Transaction> list = transactionMapper.select(transaction);
        PageInfo pageInfo = new PageInfo(list);
        PageInfo<TransactionVO> result = (PageInfo<TransactionVO>) BeanUtil.beanList2VOList(pageInfo, TransactionVO.class);
        result.getList().forEach(obj -> obj.setTokenName(configService.getNameByTokenId(obj.getTokenId())));
        return result;
    }

    public void approval(BigInteger id, Integer status, String hash) throws Exception {
        Assert.isTrue(status != 2, MessageConstants.getMsg("STATUS_ERROR"));
        Transaction transaction = new Transaction();
        transaction.setId(id);
        transaction.setStatus(status);
        transactionMapper.updateByPrimaryKeySelective(transaction);
        setBalance(id, status);
        // 发起提币
        sendValue(id, status, hash);
    }

    private void setBalance(BigInteger id, Integer status) {
        if (status.equals(9)) {
            Transaction transaction = new Transaction();
            transaction.setId(id);
            transaction = transactionMapper.selectByPrimaryKey(transaction);
            capitalMapper.updateBalance(transaction.getUserId(), transaction.getTokenId(), transaction.getNumber());
        }
    }

    private void sendValue(BigInteger id, Integer status, String hash) throws Exception {
        if (status.equals(1)) {
            Assert.notNull(hash, "hash地址不能为空");
            Transaction transaction = new Transaction();
            transaction.setId(id);
            transaction = transactionMapper.selectByPrimaryKey(transaction);
//            String contractAddress = null;
//            BigInteger value = Web3jUtil.getWei(transaction.getRealNumber(), transaction.getTokenId(), redisTemplate);
//            contractAddress = getContractAddressByTokenId(transaction);
//            String hash = sendTransaction(defaultUser, transaction.getToAddress(), contractAddress, value, true);
            transaction.setHash(hash);
            EthGetTransactionReceipt result = web3j.ethGetTransactionReceipt(hash).send();
            if (null != result && null != result.getResult() && !result.hasError() && result.getTransactionReceipt().get().getStatus().equalsIgnoreCase("0x1")) {
                transaction.setStatus(CommonConstants.STATUS_SUCCESS);
            } else {
                redisTemplate.opsForValue().set(RedisConstants.LISTEN_HASH + "#" + hash, 1);
            }
            transactionMapper.updateByPrimaryKeySelective(transaction);
        }
    }

    private String getContractAddressByTokenId(Transaction transaction) {
        return configService.getByTokenId(transaction.getTokenId()).getContractAddress();
    }

    private String sendTransaction(String fromAddress, String toAddress, String contractAddress, BigInteger realNumber, Boolean listen) throws Exception {
        PersonalUnlockAccount flag = admin.personalUnlockAccount(fromAddress, password).send();
        Assert.isTrue(flag.accountUnlocked(), "unlock error");
        org.web3j.protocol.core.methods.request.Transaction transaction = new org.web3j.protocol.core.methods.request.Transaction(
                fromAddress,
                null,
                DEFAULT_GAS_PRICE,
                DEFAULT_GAS_LIMIT,
                toAddress,
                realNumber,
                null
        );
        EthSendTransaction result = null;
        if (null == contractAddress) {
            // send eth
            result = web3j.ethSendTransaction(transaction).send();
        } else {
            // send token
            result = contractService.eth_sendTransaction(transaction, contractAddress);
        }
        Assert.isTrue(result != null && !result.hasError(), null != result.getError() ? result.getError().getMessage() : "发送失败");
        if (listen) {
            redisTemplate.opsForValue().set(RedisConstants.LISTEN_HASH + "#" + result.getTransactionHash(), 1);
        }
        return result.getTransactionHash();
    }

    @Async
    public void startListen() throws InterruptedException {
        try {
            // listen new transaction
            newListen();
        } catch (Exception e) {
            Thread.sleep(10000);
            startListen();
        }
    }

    private void newListen() throws InterruptedException {
        Subscription subscribe = null;
        try {
            subscribe = web3j.transactionObservable().subscribe(
                    tx -> listenTx(tx, false),
                    getOnError()
            );
        } catch (Exception e) {
            e.printStackTrace();
        }
        while (true) {
            if (null == subscribe || subscribe.isUnsubscribed()) {
                Thread.sleep(10000);
                newListen();
            }
        }
    }

    private Action1<Throwable> getOnError() {
        return new Action1<Throwable>() {
            @Override
            public void call(Throwable throwable) {
                log.error(throwable);
            }
        };
    }

    private void listenTx(org.web3j.protocol.core.methods.response.Transaction tx, Boolean updateLastBlockNumber) {
        addressHandler(tx);
        hashHandler(tx);
        if (updateLastBlockNumber) {
            redisTemplate.opsForValue().set(RedisConstants.LAST_BOLCK_NUMBER, tx.getBlockNumber());
        }
    }

    private void hashHandler(org.web3j.protocol.core.methods.response.Transaction tx) {
        try {

            String key = RedisConstants.LISTEN_HASH + "#" + tx.getHash();
            String hash = tx.getHash();
            if (!redisTemplate.hasKey(key)) {
                return;
            }
            Transaction transaction = new Transaction();
            transaction.setHash(hash);
            transaction = transactionMapper.selectOne(transaction);
            if (null == transaction) {
                return;
            }
            if (web3j.ethGetTransactionByHash(hash).send().hasError()) {
                transaction.setStatus(CommonConstants.ERROR);
            } else {
                transaction.setStatus(CommonConstants.STATUS_SUCCESS);
            }
            transactionMapper.updateByPrimaryKeySelective(transaction);
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.error(e);
        }
    }

    private void addressHandler(org.web3j.protocol.core.methods.response.Transaction tx) {
        String to = Web3jUtil.getTo(tx);
        String key = RedisConstants.LISTEN_ETH_ADDR + "#" + to;
        String hash = tx.getHash();
        BigInteger userId = (BigInteger) redisTemplate.opsForValue().get(key);
        if (null == userId) {
            return;
        }
        if (existHash(hash)) {
            return;
        }
        BigInteger tokenId = getTokenId(tx);
        Config config = configService.get(tokenId);
        if (null == config) {
            return;
        }
        // 充值
        Transaction transaction = new Transaction();
        transaction.setHash(hash);
        transaction.setUserId(userId);
        transaction.setTokenId(tokenId);
        transaction.setOrderId(getOrderId(CommonConstants.ORDER_RECHARGE));
        transaction.setType(CommonConstants.RECHARGE);
        transaction.setStatus(CommonConstants.STATUS_SUCCESS);
        transaction.setToAddress(to);
        transaction.setFromAddress(tx.getFrom());
        BigInteger value = Web3jUtil.isContractTx(tx) ? new BigInteger(tx.getInput().substring(tx.getInput().length() - 64), 16) : tx.getValue();
        transaction.setNumber(Web3jUtil.getValue(value, transaction.getTokenId(), redisTemplate));
        transaction.setPoundage(0f);
        transaction.setRealNumber(transaction.getNumber());
        transactionMapper.insertSelective(transaction);
        // update balance
        updateBalance(transaction);
        // transfer balance
        this.transferBalance(transaction, coldUser);
    }

    private void updateBalance(Transaction transaction) {
        Capital capital = new Capital();
        capital.setUserId(transaction.getUserId());
        capital.setTokenId(transaction.getTokenId());
        Capital capitalTemp = capitalMapper.selectOne(capital);
        if (null == capitalTemp) {
            capital.setBalance(transaction.getNumber());
            capitalMapper.insert(capital);
        } else {
            capitalMapper.updateBalance(transaction.getUserId(), transaction.getTokenId(), transaction.getNumber());
        }
    }

    @Async
    public void transferBalance(Transaction transaction, String address) {
        try {
            EthGetBalance result = web3j.ethGetBalance(transaction.getToAddress(), DefaultBlockParameterName.LATEST).send();
            BigInteger needBalance = TransactionService.DEFAULT_GAS_LIMIT.multiply(TransactionService.DEFAULT_GAS_PRICE);
            BigInteger sendBalance = result.getBalance().subtract(needBalance);
            if (sendGasIfNull(transaction, result, needBalance)) {
                return;
            }
            String contractAddress = getContractAddressByTokenId(transaction);
            if (!transaction.getTokenId().equals(BigInteger.ZERO)) {
                // erc20 token
                sendBalance = contractService.balanceOf(contractAddress, transaction.getToAddress());
            }
            sendTransaction(transaction.getToAddress(), address, contractAddress, sendBalance, false);
        } catch (Exception e) {
            e.printStackTrace();
            log.error(e.getMessage());
        }
    }

    private boolean sendGasIfNull(Transaction transaction, EthGetBalance result, BigInteger needBalance) throws IOException {
        if (!result.hasError() && result.getBalance().compareTo(BigInteger.ZERO) == 0) {
            org.web3j.protocol.core.methods.request.Transaction trans = new org.web3j.protocol.core.methods.request.Transaction(
                    defaultUser,
                    null,
                    DEFAULT_GAS_PRICE,
                    DEFAULT_GAS_LIMIT,
                    transaction.getToAddress(),
                    needBalance,
                    null
            );
            admin.personalUnlockAccount(defaultUser, password);
            web3j.ethSendTransaction(trans).send();
            redisTemplate.opsForList().leftPush(RedisConstants.GAS_QUENE, transaction);
            return true;
        }
        return false;
    }

    private BigInteger getTokenId(org.web3j.protocol.core.methods.response.Transaction tx) {
        if (Web3jUtil.isContractTx(tx)) {
            BigInteger tokenId = configService.getIdByContractAddress(tx.getTo());
            return tokenId;
        } else {
            return BigInteger.ZERO;
        }
    }

    private Boolean existHash(String hash) {
        Transaction transaction = new Transaction();
        transaction.setHash(hash);
        if (null != transactionMapper.selectOne(transaction)) {
            // 充值记录已存在, 充值不存在成功以外的状态
            return true;
        }
        return false;
    }

    private void historyListen() throws InterruptedException {
        BigInteger lastBlockNumber = (BigInteger) redisTemplate.opsForValue().get(RedisConstants.LAST_BOLCK_NUMBER);
        if (null == lastBlockNumber) {
            lastBlockNumber = BigInteger.ZERO;
        }
        Subscription subscription = web3j.replayTransactionsObservable(DefaultBlockParameter.valueOf(lastBlockNumber), DefaultBlockParameterName.LATEST).subscribe(
                tx -> listenTx(tx, true),
                getOnError()
        );
        while (true) {
            if (null == subscription || subscription.isUnsubscribed()) {
                Thread.sleep(10000);
                historyListen();
            }
        }
    }

    public Integer newAddress() throws IOException {
        Account account = null;
        Integer num = 0;
        while (null != (account = accountService.getNonAddress())) {
            NewAccountIdentifier result = admin.personalNewAccount(password).send();
            account.setAddressEth(result.getAccountId());
            accountService.update(account);
            redisTemplate.opsForValue().set(RedisConstants.LISTEN_ETH_ADDR + "#" + result.getAccountId(), account.getId());
            num++;
        }
        return num;
    }

    public Integer sendGas() {
        Integer number = 0;
        while (redisTemplate.opsForList().size(RedisConstants.GAS_QUENE) > 0) {
            Transaction transaction = (Transaction) redisTemplate.opsForList().rightPop(RedisConstants.GAS_QUENE);
            transferBalance(transaction, defaultUser);
            number++;
        }
        return number;
    }

    public void initConfig() {
        configService.initUnit();
    }

    @Async
    public void startHistory() throws InterruptedException {
        try {
            // listen history transaction
            historyListen();
        } catch (Exception e) {
            Thread.sleep(10000);
            startHistory();
        }
    }
}
