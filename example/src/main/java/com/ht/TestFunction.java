package com.ht;

import java.util.function.BiConsumer;

/**
 * @author HT
 * @version V1.0
 * @package com.ht
 * @date 2019-06-15 12:41
 */
public class TestFunction {
    public static void main(String[] args) {
//        User user = new User();
//        Student student = new Student();
//        System.out.println(printAge(user));
        System.out.println(getAge(TestEnum.STUDENT));
    }

    public static int getAge(TestEnum person) {
        return person.getAge();
    }

    public static int printAge(User user) {
        return printAge(user, null);
    }

    public static int printAge(Student student) {
        return printAge(null, student);
    }

    public static int printAge(User user, Student student) {
        if((user == null && student == null)
            || (user != null && student != null)) {
            throw new IllegalArgumentException("传入参数有误");
        }
        if(user != null) {
            return user.getAge();
        }

        return student.getAge();
    }
}
