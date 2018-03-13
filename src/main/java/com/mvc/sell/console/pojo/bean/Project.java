package com.mvc.sell.console.pojo.bean;

import com.mvc.sell.console.constants.MessageConstants;
import com.sun.tools.internal.xjc.reader.xmlschema.bindinfo.BIInlineBinaryData;
import lombok.Data;
import org.web3j.abi.datatypes.Int;

import javax.validation.constraints.NotNull;
import java.math.BigInteger;
import java.util.Date;

/**
 * Project
 *
 * @author qiyichen
 * @create 2018/3/13 11:17
 */
@Data
public class Project {

    private BigInteger id;
    @NotNull(message = MessageConstants.TITLE_EMPTY)
    private String title;
    private String tokenName;
    private Float ratio;
    private Date startTime;
    private Date stopTime;
    private String whitePaperAddress;
    private String whitePaperName;
    private String projectImageAddress;
    private String projectImageName;
    private String leaderImageAddress;
    private String leaderImageName;
    private String leaderName;
    private String position;
    private String description;
    private Date createdAt;
    private Date updatedAt;

}
