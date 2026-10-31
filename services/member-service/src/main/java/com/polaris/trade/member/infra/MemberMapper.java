package com.polaris.trade.member.infra;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.polaris.trade.member.domain.MemberEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MemberMapper extends BaseMapper<MemberEntity> {

    @Select("SELECT * FROM t_member WHERE user_id = #{userId} LIMIT 1")
    MemberEntity findByUserId(@Param("userId") String userId);

    @Select("SELECT * FROM t_member WHERE phone_hash = #{phoneHash} LIMIT 1")
    MemberEntity findByPhoneHash(@Param("phoneHash") String phoneHash);
}
