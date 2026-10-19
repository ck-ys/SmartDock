package com.smartdock.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * AI provider 余额信息。
 *
 * @author smartdock
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DcsAiBalanceVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Boolean available;

    private String message;

    private List<BalanceInfo> balances = new ArrayList<>();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BalanceInfo implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private String currency;

        private String totalBalance;

        private String grantedBalance;

        private String toppedUpBalance;
    }
}
