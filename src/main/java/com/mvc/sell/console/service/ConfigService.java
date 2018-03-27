package com.mvc.sell.console.service;

import com.mvc.sell.console.constants.RedisConstants;
import com.mvc.sell.console.pojo.bean.Config;
import org.springframework.stereotype.Service;
import org.web3j.utils.Convert;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

/**
 * ConfigService
 *
 * @author qiyichen
 * @create 2018/3/13 11:02
 */
@Service
public class ConfigService extends BaseService {


    public List<Config> list() {
        return configMapper.selectAll();
    }

    public void insert(Config config) {
        String tokenName = config.getTokenName();
        Config configTemp = getConfigByTokenName(tokenName);
        if (null != configTemp) {
            config.setId(configTemp.getId());
            return;
        }
        configMapper.insertSelective(config);
        setUnit(config.getId(), config.getDecimals());
    }

    public Config getConfigByTokenName(String tokenName) {
        Config config = new Config();
        config.setTokenName(tokenName);
        config = configMapper.selectOne(config);
        return config;
    }

    public void update(Config config) {
        insert(config);
        configMapper.updateByPrimaryKeySelective(config);
    }

    public List<String> token() {
        return configMapper.token();
    }

    public Config get(BigInteger tokenId) {
        if (null == tokenId) {
            return null;
        }
        Config config = new Config();
        config.setId(tokenId);
        config.setRechargeStatus(1);
        return configMapper.selectOne(config);
    }

    public Config getByTokenId(BigInteger tokenId) {
        Config config = new Config();
        config.setId(tokenId);
        return configMapper.selectByPrimaryKey(config);
    }

    private void setUnit(BigInteger id, Integer decimals) {
        Arrays.stream(Convert.Unit.values()).forEach(obj -> {
                    int value = obj.getWeiFactor().toString().length() - 1;
                    if (decimals == value) {
                        redisTemplate.opsForValue().set(RedisConstants.UNIT + "#" + id, obj);
                    }
                }
        );
    }

    public BigInteger getIdByContractAddress(String contractAddress) {
        Config config = new Config();
        config.setContractAddress(contractAddress);
        return configMapper.selectOne(config).getId();
    }
}
