// Only reviewed business fields are rendered. Service payloads and internal evidence never
// fall back to JSON.stringify in a user-facing answer.
export const BUSINESS_LABELS = {
  outstandingQuantity: '未实物归还数量', businessDirection: '业务方向',
  periodStart: '应对账期间起', periodEnd: '应对账期间止', scheduledDate: '计划对账日',
  reconciliationType: '对账类型', reconciliationPeriodMonths: '对账周期（月）',
  settledRentAmount: '已结算材料租金', plannedDate: '计划对账日', cycleMonths: '周期（月）', anchorDate: '起算日期', notYetDue: '尚未到对账日',
  settledRent: '已结算材料租金', materialRentFee: '材料租金', currency: '币种',
  pendingPeriods: '待对账期间',
  periodStatus: '对账状态', scheduledEndDate: '应对账截止日',

  projectName: '项目名称', constructionProject: '施工项目', projectId: '项目编号', managerName: '我方负责人',
  partnerName: '对方负责人', tenantUnit: '承租单位', customerName: '客户', supplierName: '供应商',
  projectBusinessType: '业务类型', projectStatusFlag: '项目状态', address: '地址', city: '城市',
  materialName: '材料名称', materialSpecification: '规格', specification: '规格', categoryName: '分类',
  materialCategory: '材料分类', countingUnit: '计数单位', pricingUnit: '计价单位', unit: '单位',
  materialId: '材料编号', mid: '材料编号', availableQty: '可供数量', inventoryQty: '库存数量',
  inventoryQuantity: '库存数量', quantity: '数量', materialNumber: '材料数量', countingQuantity: '计数数量',
  inRentQuantity: '在租数量', rentOutQuantity: '租出数量', returnQuantity: '归还数量', rentInQuantity: '租入数量',
  rentInReturnQuantity: '退租数量', marketPrice: '报价', marketPriceUnit: '报价单位', marketUpdateTime: '报价更新时间',
  stationName: '租赁站', contactPerson: '联系人', contactPhone: '联系电话', phone: '电话',
  documentName: '单据名称', documentId: '单据编号', rentDocumentName: '租出单名称', returnDocumentName: '归还单名称',
  compensationDocumentName: '赔偿单名称', rentInDocumentName: '租入单名称', rentInReturnDocumentName: '退租单名称',
  rentDocumentId: '租出单编号', returnDocumentId: '归还单编号', compensationDocumentId: '赔偿单编号',
  rentInDocumentId: '租入单编号', rentInReturnDocumentId: '退租单编号', reviewStatus: '复核状态',
  rentDate: '租出日期', returnDate: '归还日期', rentInDate: '租入日期', rentInReturnDate: '退租日期',
  compensationDate: '赔偿日期', businessDate: '业务日期', documentDate: '单据日期', createDate: '创建日期',
  date: '日期', startDate: '开始日期', endDate: '结束日期', paymentDate: '付款日期', checkDate: '盘点日期',
  createdAt: '创建时间', updatedAt: '更新时间', personInCharge: '负责人', auditor: '复核人', creator: '制表人',
  shipper: '发货人', carrier: '承运人', driver: '司机', driverName: '司机', vehicleNo: '车牌号',
  transportVehicleNo: '运输车号', contactInfo: '联系方式', driverContactPhone: '司机电话',
  deliveryLocation: '发货地点', toLocation: '去向地点', tenantManager: '承租方负责人', tenantContact: '承租方联系人',
  tenantContactPhone: '承租方电话', rentalStation: '租赁站', note: '备注', remark: '备注',
  name: '名称', personName: '姓名', duty: '职务', billingUnitName: '开票单位', taxNumber: '税号',
  paymentType: '缴费类型', paymentAmount: '登记金额', amount: '金额', totalAmount: '合计金额',
  paymentMethod: '付款方式', paymentName: '付款名称', paidAmount: '已付金额', unpaidAmount: '未付金额',
  rentPrice: '租金单价', rentalPrice: '租金单价', compensationPrice: '赔偿单价', dailyRent: '日租金',
  taxRate: '税率', settlementCycle: '结算周期', contractStartDate: '合同开始日期', contractEndDate: '合同结束日期',
  taskName: '盘点名称', taskId: '盘点编号', expectedQuantity: '账面数量', actualQuantity: '实盘数量',
  countedQuantity: '已盘数量', diffQuantity: '差异数量', uncheckedDays: '未盘天数',
  estimateName: '预估名称', estimateId: '预估编号', estimatedQty: '预估数量', requiredQty: '需求数量',
  shortageQty: '缺口数量', suggestion: '建议', supported: '是否支持', reason: '说明', summary: '概况',
  inventoryBalance: '库存余额', beforeQuantity: '变动前数量', afterQuantity: '变动后数量', changeQuantity: '变动数量',
  behaviorType: '业务类型', totalNum: '全部匹配数', current: '当前页',
  dashboard: '库存概况', kpi: '指标', anomalySummary: '异常种类', taskList: '盘点任务', anomalyList: '库存异常',
  totalAnomalyMaterials: '异常材料种类', negativeInventoryCount: '负库存材料种类', negativeRentedCount: '在租为负种类',
  negativeLeasedCount: '租入未退为负种类', stagnantCount: '长期无流水种类',
  systemInventoryQty: '账面库存', countedQty: '实盘数量', diffQty: '差异数量', expectedQty: '账面数量',
  totalItems: '材料种类', countedItems: '已盘种类', diffItems: '差异种类', diffAmount: '差异金额', diffRate: '差异率',
  siteCount: '工地数量', materialCount: '材料种类', anomalySiteCount: '异常工地数', uncheckedSiteCount: '未盘点工地数',
  diffMaterialCount: '差异材料种类', rentQuantity: '租出数量', compensationQuantity: '赔偿数量', latestCountedQty: '最近实盘数量',
  eventDate: '业务日期', behaviorName: '业务名称', delta: '变动数量', beforeExpected: '变动前账面数量', afterExpected: '变动后账面数量',
  statusName: '状态', documentOwner: '单据负责人', rentSideOwner: '租出负责人', returnSideOwner: '归还负责人',
  categories: '分类', materials: '材料', ladders: '阶梯价', contractPricingUnit: '合同计价单位',
  compensationUnitPrice: '赔偿单价', predictRentNumber: '预计租赁数量', segmentPriceSummary: '阶梯价概况',
  loadingFeeUnitPrice: '装车费单价', unloadingFeeUnitPrice: '卸车费单价', freightUnitPrice: '运费单价', packingFeeUnitPrice: '打包费单价',
  miscFeeDetails: '杂费明细', majorCategoryName: '杂费大类', miscfeeCategoryName: '杂费名称', unitPrice: '单价',
  billingUnits: '开票单位', progress: '进度', predictQuantity: '预计数量', reportBriefInfos: '报告列表', reportData: '报告内容',
  reportId: '报告编号', periodType: '报告周期', settlementPeriodList: '结算账期', firstBusinessDate: '首笔业务日期', latestBusinessDate: '最近业务日期',
  reconciliationDocumentName: '物料对账单', reconciliationDate: '对账日期', warehouseName: '仓库', timePeriod: '时间范围',
  actualAmount: '登记金额', allocatedAmount: '已分配核销金额', unallocatedAmount: '未分配金额',
  documentBriefInfoList: '单据列表', personnelInfos: '人员列表', transportInfos: '运输资料', materialInfos: '材料明细',
  inventoryUnit: '库存单位', rentedQuantity: '在租数量', leasedQuantity: '租入未退数量', totalQuantity: '总量',
  procurementInboundQuantity: '采购入库数量', lastFlowDate: '最近流水日期', rentalUnit: '计价单位',
  dailyRentV2: '日租金', materialValue: '材料价值', minRentDays: '最少租赁天数',
  shortageItems: '缺口材料', sufficientItems: '库存充足材料', recommendations: '推荐站点',
  demandQty: '需求数量', gapQty: '缺口数量', recommendReason: '推荐说明', inventoryMatch: '库存匹配',
  enoughCount: '充足材料种类', shortageCount: '缺货材料种类', buildingArea: '建筑面积',
  floorAbove: '地上层数', floorBelow: '地下层数', buildingHeight: '建筑高度', rentalDays: '租赁天数',
  region: '地区', scopeSummary: '估算范围', scopeNotices: '范围说明', calculationSummary: '计算说明',
  thisRentFee: '本期租金', thisCompensationFee: '本期赔偿', thisTotalFee: '本期合计金额',
  thisMiscFee: '本期杂费', accumulatedRentFee: '累计租金', accumulatedTotalFee: '累计合计金额',
  rentFeeInfos: '租金明细', compensationFeeInfos: '赔偿明细', incidentalFeeInfos: '杂费明细',
  list: '查询结果', items: '明细', records: '记录', materialInfoList: '材料明细', materialRentInfoList: '租出材料',
  materialReturnInfoList: '归还材料', materialCompensationInfoList: '赔偿材料', materialRentInInfoList: '租入材料',
  materialRentInReturnInfoList: '退租材料', paymentList: '登记收付款', personnelInfoList: '人员',
  incidentalList: '杂费', personnelInfo: '人员信息', relatedDocuments: '关联单据',
  rentOutProgress: '租出进度', returnProgress: '归还进度', rentInProgress: '租入进度', rentInReturnProgress: '退租进度',
  // 第二阶段与第三阶段扩展字段
  conclusion30s: '30秒结论', draftStatus: '草稿状态', hitlConfirmationRequired: '需人工复核确认', actionHint: '下一步操作建议',
  documentTypeLabel: '业务单据类型', targetPageName: '目标业务页面',
  estimatedDailyRent: '预计每日租金合计(元/天)', estimatedItemDailyRent: '预计单项日租金(元/天)',
  operationSteps: '建单操作步骤引导', inventoryCheckList: '基地库存充足度核查',
  contractPricingList: '项目合同单价与杂费参考', requestedMaterial: '计划材料与规格',
  requestedQty: '计划数量', matchedMaterialId: '匹配库存编码',
  matchedMaterialName: '匹配材料名称', matchedSpecification: '匹配规格',
  stockCheckStatus: '库存充足度结论', pricingCheckStatus: '合同配价状态',
  miscFeeStandard: '装卸运输杂费标准',
  periodTotalFee: '本期应结总额', periodRentFee: '本期基础租金', incrementRentTotal: '本期超期递增租金',
  periodCompensationFee: '本期赔偿费用', periodIncidentalFee: '本期杂费合计',
  stoppageDeductionAmount: '报停减免金额', negotiationDiscountAmount: '协商让利金额', settlementRows: '租金试算明细',
  title: '预览标题', materialRowCount: '核对材料行数', warningCount: '校验提示数',
  validationWarnings: '校验提示', validationErrors: '阻断错误', previewRows: '对账预览明细',
  sectionTitle: '所属区块', openingQty: '期初结存数量', periodRentQty: '本期租出数量',
  periodReturnQty: '本期归还数量', periodCompensationQty: '本期赔偿数量', closingQty: '期末结存数量',
  dailyRatePercent: '日利率(%)', overduePeriodCount: '逾期账期数',
  accumulatedAccruedAmount: '累计应计滞纳金', accumulatedPaidAmount: '累计已收滞纳金', currentUnpaidAmount: '当前未结清滞纳金',
  periods: '逾期账期明细', dueDate: '应付截止日', openingPrincipal: '期初欠款本金',
  closingPrincipal: '期末未付本金', overdueDays: '逾期天数', lateFeeAmount: '应计滞纳金',
  pendingBatchCount: '待确认迁移批次数', pendingItemCount: '待映射旧系统材料数', aliasCount: '标准别名字典数',
  missingUnitCount: '缺失双单位材料数', duplicateSpecCount: '同名同规格重复组数',
  masterAnomalies: '主数据异常明细', migrationItems: '待映射迁移条目', aliases: '材料别名字典',
  issueType: '问题类型', detail: '详情说明', rawMaterialName: '原系统材料名', rawSpecification: '原系统规格',
  rawUnit: '原系统单位', suggestedMaterialName: '推荐标准材料', mappingStatus: '映射状态',
  aliasName: '别名名称', canonicalMaterialName: '标准材料名称',
  decisionVerdict: '接单评估结论', fulfillmentRate: '品种库存满足率(%)', totalGapQty: '总缺口数量',
  marketRecommendations: '租赁商城补货推荐',
  complianceLevel: '合规风险等级', findingCount: '风险项总数', configuredMaterialCount: '已配置合同材料数',
  zeroPriceCount: '零单价或待映射材料数', unpricedDeliveryCount: '无合同定价已发料种类',
  afterExpiryDeliveryCount: '超合同到期日发料单数', unreviewedDocCount: '未复核业务单据数',
  findings: '合规审查发现项', findingCode: '规则编码', severity: '风险级别',
  baselineDailyRent: '基准日租金合计', leaseDays: '测算基准租期(天)', baselineTotalRent: '原价周期总租金',
  scenarios: '报价让利沙盘方案', scenarioName: '方案名称', discountPercent: '折扣比例(%)',
  freeRentDays: '免租天数', effectiveBillingDays: '实际计费天数', discountedDailyRent: '折后日租金',
  scenarioTotalRent: '方案周期总租金', concessionAmount: '合计让利金额',
  extraDaysToRecoverConcession: '追回让利需延长租期(天)', breakevenTotalLeaseDays: '盈亏平衡总租期(天)',
  currentSettlementId: '本期结算单号', currentPeriod: '本期结算区间', currentTotalFee: '本期结算总额',
  previousSettlementId: '上期结算单号', previousPeriod: '上期结算区间', previousTotalFee: '上期结算总额',
  totalDelta: '跨期总额变动', factorBreakdown: '金额变动构成明细', factorCode: '变动项编码',
  factorName: '变动项名称', previousAmount: '上期金额', currentAmount: '本期金额',
  deltaAmount: '变动差额', sharePercent: '占变动总额比例(%)', materialDeltas: '材料级租金变动排行',
  previousRent: '上期材料租金', currentRent: '本期材料租金', deltaRent: '材料租金差额',
  starCount: '高利用率（紧缺）材料数', cashCowCount: '中利用率（正常周转）材料数', questionCount: '低利用率（待消化）材料数',
  deadStockCount: '长期闲置积压材料数', unpublishedDeadStockCount: '未上架商城闲置材料数',
  utilizationRatePercent: '当前出租率(%)', daysSinceLastFlow: '连续无流水天数',
  bcgQuadrant: '材料利用率分档', marketPublished: '已上架商城', strategyAdvice: '调拨与去库存建议',
  counterpartyCount: '评估合作方总数', gradeACount: 'A级优质守约数', gradeBCount: 'B级良性正常数',
  gradeCCount: 'C级关注预警数', gradeDCount: 'D级高危风控数', profiles: '合作方履约回款明细',
  counterpartyName: '合作单位名称', counterpartyDirection: '往来方向', creditGrade: '信用评级',
  projectCount: '关联项目数', settledPrincipal: '正式结算本金', allocatedPrincipal: '已核销本金',
  outstandingPrincipal: '未结清本金', overduePrincipal: '逾期未付本金',
  fulfillmentRatePercent: '本金履约核销率(%)', maxOverdueDays: '最长逾期天数',
  compensationDocCount: '累计赔偿单次数', riskAdvice: '授信与催收建议',
  opportunityCount: '可替换省钱项数', totalEstimatedDailySavings: '预计每日可省外租费',
  totalEstimatedMonthlySavings: '预计每月可省外租费', opportunities: '自有库存替换外租清单',
  rentInProjectId: '租入项目编号', rentInProjectName: '租入项目名称',
  warehouseIdleQty: '仓库自有闲置量', externalUnreturnedQty: '外部租入未退量',
  replaceableQty: '可立即替换退租量', unitDailyRent: '外租日租金单价',
  estimatedDailySavings: '预计日省租金', estimatedMonthlySavings: '预计月省租金',
  supplySiteCount: '可调出工地材料项数', demandSiteCount: '待补料工地需求项数',
  transferPairCount: '直调配对方案组数', transferPlans: '跨工地就近调拨方案',
  fromProjectId: '调出项目编号', fromProjectName: '调出工地名称',
  toProjectId: '调入项目编号', toProjectName: '调入工地名称',
  transferQty: '建议直调数量', operationAdvice: '直调执行建议',
};

export const businessValue = (key, value) => {
  if (value === null || value === undefined || value === '') return '未填写';
  if (typeof value === 'boolean') return value ? '是' : '否';
  if (key === 'reviewStatus') return ({ 0: '未复核', 1: '已复核' })[String(value)] || '状态待核实';
  if (key === 'projectBusinessType') return ({ rent_out: '租出', rent_in: '租入' })[value] || String(value);
  if (key === 'draftStatus') {
    return ({
      GUIDED_PRECHECK_AND_NAVIGATION: '建单前置核查与操作引导（请核对下方库存与单价后，点击底部按钮直达业务页面正式建单）',
      DRAFT_PREVIEW_ONLY: '试算草稿预览（未正式入账，核对无误后请前往对应页面保存）',
      TRIAL_CALCULATION_ONLY: '试算结果预览（未生成正式账单）',
    })[value] || String(value);
  }
  if (key === 'decisionVerdict') {
    return ({
      CAN_ACCEPT_DIRECTLY: '自有现货充足，可直接接单',
      ACCEPT_WITH_MARKET_SOURCING: '建议接单（缺口可商城寻源补货）',
      HIGH_SHORTAGE_CAUTION: '缺口较大，建议先落实外部货源再接单',
      NO_MATCHING_MATERIAL: '未匹配到对应库存材料',
    })[value] || String(value);
  }
  if (key === 'complianceLevel') {
    return ({ PASS: '合规通过', WARNING: '存在预警', HIGH_RISK: '高风险待整改' })[value] || String(value);
  }
  if (key === 'bcgQuadrant') {
    return ({
      STAR: '利用率高（紧缺）',
      CASH_COW: '利用率中（正常周转）',
      QUESTION: '利用率低（待消化）',
      DOG_DEAD_STOCK: '利用率低（长期闲置）',
    })[value] || String(value);
  }
  if (key === 'counterpartyDirection') {
    return ({ CUSTOMER: '租出客户', SUPPLIER: '租入供应商' })[value] || String(value);
  }
  if (typeof value === 'object') return '';
  return String(value);
};

// Copy only accepted fields; a model cannot supply a route, arbitrary URL or executable action.
export const BUSINESS_PAGES = Object.freeze({
  PROJECT_WORKFLOW: ['2-1', '项目业务页面'],
  PROJECT_RENT_OUT: ['2-1', '租出项目'], PROJECT_RENT_IN: ['2-2', '租入项目'],
  INVENTORY: ['3-0', '库存工作台'], PROCUREMENT: ['3-1', '采购入库'], INBOUND: ['3-2', '其他入库'],
  OUTBOUND: ['3-3', '其他出库'], WAREHOUSE_COUNT: ['3-4', '仓库盘点'], TEMPORARY_STORAGE: ['3-5', '暂存'],
  TEMPORARY_RETURN: ['3-6', '退还'], SITE_DISTRIBUTION: ['3-7', '工地材料分布'],
  MATERIALS: ['4-1', '材料管理'], PERSONNEL: ['4-4', '人员管理'], BILLING_UNITS: ['4-6', '开票单位'],
  ESTIMATE: ['5-2', '材料预估'], MARKET: ['5-3', '材料商城'], HELP: ['help', '帮助中心'],
});
export const normalizeBusinessNavigation = (input) => {
  if (!input || typeof input !== 'object' || !Object.prototype.hasOwnProperty.call(BUSINESS_PAGES, input.target)) return null;
  const result = { target: input.target, menuKey: BUSINESS_PAGES[input.target][0], filters: {} };
  if (input.target === 'PROJECT_WORKFLOW') {
    const projectId = input.filters?.projectId;
    const workflow = input.filters?.workflow || 'overview';
    if (typeof projectId !== 'string' || !/^[a-zA-Z0-9_-]{1,100}$/.test(projectId)) return null;
    if (!['overview', 'contract', 'projectMaterial', 'rentOut', 'returnOrder', 'compensationOrder', 'rentIn', 'rentInReturn', 'materialReconciliation', 'customerPayment', 'financeCheck', 'lateFeeDocument'].includes(workflow)) return null;
    result.filters = { projectId, workflow };
  }
  if (input.target === 'MARKET') {
    ['keyword', 'city', 'categoryName', 'materialSpecification', 'countingUnit', 'marketPriceUnit', 'stationCid'].forEach((key) => {
      const value = input.filters?.[key];
      if (typeof value === 'string' && value.length <= 200) result.filters[key] = value;
    });
    if (Number.isFinite(input.filters?.minAvailableQty) && input.filters.minAvailableQty >= 0) result.filters.minAvailableQty = input.filters.minAvailableQty;
    if (input.filters?.sortField === 'PRICE_ASC' && result.filters.countingUnit && result.filters.marketPriceUnit) result.filters.sortField = 'PRICE_ASC';
  }
  return result;
};

export const requestBusinessNavigation = (input) => {
  const safe = normalizeBusinessNavigation(input);
  if (!safe) return false;
  window.dispatchEvent(new CustomEvent('agent:business-navigation', { detail: safe }));
  return true;
};

// User vs. Dev/Ops view gate: ordinary business users get a clean, jargon-free interface;
// developers/admins can append ?agentDebug=true (or localStorage ZJYZ_AGENT_DEBUG=true) to inspect
// memory verification states, RAG chunk/token debuggers, and internal evidence IDs.
export const isAgentDebugMode = (locationLike, storageLike) => {
  try {
    const loc = locationLike || (typeof window !== 'undefined' ? window.location : null);
    const search = `${loc?.search || ''}&${(loc?.hash || '').split('?')[1] || ''}`;
    if (/(?:^|[?&])agentDebug=(?:1|true)(?:&|$)/i.test(search)) return true;
    const store = storageLike || (typeof window !== 'undefined' ? window.localStorage : null);
    if (store && String(store.getItem('ZJYZ_AGENT_DEBUG')).toLowerCase() === 'true') return true;
  } catch (_) {
    // Ignore storage access errors in restricted webviews.
  }
  return false;
};

export const formatDueDateBasisLabel = (basis) => {
  const map = {
    SETTLEMENT_PERIOD_END: '结算账期结束日',
    CONTRACT_PAYMENT_DUE: '合同约定付款日',
  };
  return map[basis] || '结算账期结束日';
};

export const formatDimensionStatusLabel = (status) => {
  const map = {
    SUPPORTED: '已核算',
    FAILED: '核算异常',
    UNSUPPORTED: '暂未纳入',
    UNKNOWN: '待确认',
  };
  return map[status] || status || '待确认';
};

