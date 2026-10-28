package com.bytedance.douyinclouddemo.model;

import com.alibaba.fastjson.annotation.JSONField;
import lombok.Data;

@Data
public class LivePlayAPIResponse {
    @JSONField(name = "err_no")
    private Integer errNo;

    @JSONField(name = "err_msg")
    private String errorMsg;

    @JSONField(name = "log_id")
    private String logID;
}
