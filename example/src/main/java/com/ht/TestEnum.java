package com.ht;

/**
 * @author HT
 * @version V1.0
 * @package com.ht
 * @date 2019-06-15 13:02
 */
public enum TestEnum {
    USER {
        @Override
        public int getAge() {
            return 10;
        }
    },
    STUDENT {
        @Override
        public int getAge() {
            return 12;
        }
    };

    public abstract int getAge();
}
