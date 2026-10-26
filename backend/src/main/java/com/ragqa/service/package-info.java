/**
 * 业务服务层。
 *
 * <p>职责：编排业务逻辑、事务边界、调用 mapper 与外部组件。接口放在本包下，
 * 实现类放在 {@code service.impl} 子包，命名形如 {@code XxxService} / {@code XxxServiceImpl}。</p>
 *
 * <p>约定：</p>
 * <ul>
 *     <li>controller 只做入参校验与响应组装，业务规则写在这里</li>
 *     <li>可预期的失败抛 {@code BusinessException}，由全局异常处理器统一转换</li>
 *     <li>写操作加 {@code @Transactional}，只读查询不加</li>
 * </ul>
 */
package com.ragqa.service;
