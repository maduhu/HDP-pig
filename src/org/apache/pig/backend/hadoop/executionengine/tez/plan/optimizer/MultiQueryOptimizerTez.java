/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.pig.backend.hadoop.executionengine.tez.plan.optimizer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.commons.lang.ArrayUtils;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.PhysicalOperator;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.expressionOperators.POUserFunc;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.plans.PhysicalPlan;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.POSplit;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.util.PlanHelper;
import org.apache.pig.backend.hadoop.executionengine.tez.plan.TezEdgeDescriptor;
import org.apache.pig.backend.hadoop.executionengine.tez.plan.TezOpPlanVisitor;
import org.apache.pig.backend.hadoop.executionengine.tez.plan.TezOperPlan;
import org.apache.pig.backend.hadoop.executionengine.tez.plan.TezOperator;
import org.apache.pig.backend.hadoop.executionengine.tez.plan.operator.POValueOutputTez;
import org.apache.pig.backend.hadoop.executionengine.tez.plan.udf.ReadScalarsTez;
import org.apache.pig.backend.hadoop.executionengine.tez.runtime.TezInput;
import org.apache.pig.backend.hadoop.executionengine.tez.runtime.TezOutput;
import org.apache.pig.backend.hadoop.executionengine.tez.util.TezCompilerUtil;
import org.apache.pig.impl.plan.OperatorKey;
import org.apache.pig.impl.plan.PlanException;
import org.apache.pig.impl.plan.ReverseDependencyOrderWalker;
import org.apache.pig.impl.plan.VisitorException;

public class MultiQueryOptimizerTez extends TezOpPlanVisitor {

    private boolean unionOptimizerOn;
    private List<String> unionUnsupportedStoreFuncs;

    public MultiQueryOptimizerTez(TezOperPlan plan, boolean unionOptimizerOn, List<String> unionUnsupportedStoreFuncs) {
        super(plan, new ReverseDependencyOrderWalker<TezOperator, TezOperPlan>(plan));
        this.unionOptimizerOn = unionOptimizerOn;
        this.unionUnsupportedStoreFuncs = unionUnsupportedStoreFuncs;
    }

    private void addAllPredecessors(TezOperator tezOp, List<TezOperator> predsList) {
        if (getPlan().getPredecessors(tezOp) != null) {
            for (TezOperator pred : getPlan().getPredecessors(tezOp)) {
                predsList.add(pred);
                addAllPredecessors(pred, predsList);
            }
        }
    }

    @Override
    public void visitTezOp(TezOperator tezOp) throws VisitorException {
        try {
            if (!tezOp.isSplitter()) {
                return;
            }

            List<TezOperator> splittees = new ArrayList<TezOperator>();
            Set<TezOperator> mergedNonPackageInputSuccessors = new HashSet<TezOperator>();

            List<TezOperator> successors = getPlan().getSuccessors(tezOp);
            for (TezOperator successor : successors) {
                List<TezOperator> predecessors = new ArrayList<TezOperator>(getPlan().getPredecessors(successor));
                predecessors.remove(tezOp);
                if (!predecessors.isEmpty()) {
                    // If has other dependency that conflicts with other splittees, don't merge into split
                    // For eg: self replicate join/skewed join
                    // But if replicate input is from a different operator allow it, but ensure
                    // that we don't have more than one input coming from that operator into the split

                    // Check if other splittees or its predecessors (till the root) are not present in
                    // the predecessors (till the root) of this splittee.
                    // Need to check the whole predecessors hierarchy till root as the conflict
                    // could be multiple levels up
                    for (TezOperator predecessor : getPlan().getPredecessors(successor)) {
                        if (predecessor != tezOp) {
                            predecessors.add(predecessor);
                            addAllPredecessors(predecessor, predecessors);
                        }
                    }
                    List<TezOperator> toMergeSuccPredecessors = new ArrayList<TezOperator>(successors);
                    toMergeSuccPredecessors.remove(successor);
                    for (TezOperator splittee : splittees) {
                        for (TezOperator spliteePred : getPlan().getPredecessors(splittee)) {
                            if (spliteePred != tezOp) {
                                toMergeSuccPredecessors.add(spliteePred);
                                addAllPredecessors(spliteePred, toMergeSuccPredecessors);
                            }
                        }
                    }
                    if (predecessors.removeAll(toMergeSuccPredecessors)) {
                        continue;
                    }
                }

                // Split contains right input of different skewed joins
                if (successor.getSampleOperator() != null
                        && tezOp.getSampleOperator() != null
                        && !successor.getSampleOperator().equals(
                                tezOp.getSampleOperator())) {
                    continue;
                }

                // Detect diamond shape into successor operator, we cannot merge it into split,
                // since Tez does not handle double edge between vertexes
                // Successor could be
                //    - union operator (if no union optimizer changing it to vertex group which supports multiple edges)
                //    - self replicate join, self skewed join or scalar
                //    - POPackage (Self hash joins can write to same output edge and is handled by POShuffleTezLoad)
                Set<TezOperator> mergedSuccessors = new HashSet<TezOperator>();
                // These successors should not be merged due to diamond shape
                Set<TezOperator> toNotMergeSuccessors = new HashSet<TezOperator>();
                // These successors can be merged
                Set<TezOperator> toMergeSuccessors = new HashSet<TezOperator>();
                // These successors (Scalar, POFRJoinTez) can be merged if they are the only input.
                // Only in case of POPackage(POShuffleTezLoad) multiple inputs can be handled from a Split
                Set<TezOperator> nonPackageInputSuccessors = new HashSet<TezOperator>();
                boolean canMerge = true;

                mergedSuccessors.addAll(successors);
                for (TezOperator splittee : splittees) {
                    if (getPlan().getSuccessors(splittee) != null) {
                        mergedSuccessors.addAll(getPlan().getSuccessors(splittee));
                    }
                }
                if (getPlan().getSuccessors(successor) != null) {
                    for (TezOperator succSuccessor : getPlan().getSuccessors(successor)) {
                        if (succSuccessor.isUnion()) {
                            if (!(unionOptimizerOn
                                    && UnionOptimizer.isOptimizable(succSuccessor, unionUnsupportedStoreFuncs))) {
                                toNotMergeSuccessors.add(succSuccessor);
                            } else {
                                toMergeSuccessors.add(succSuccessor);
                                List<TezOperator> unionSuccessors = getPlan().getSuccessors(succSuccessor);
                                if (unionSuccessors != null) {
                                    for (TezOperator unionSuccessor : unionSuccessors) {
                                        if (TezCompilerUtil.isNonPackageInput(succSuccessor.getOperatorKey().toString(), unionSuccessor)) {
                                            canMerge = canMerge ? nonPackageInputSuccessors.add(unionSuccessor) : false;
                                        } else {
                                            toMergeSuccessors.add(unionSuccessor);
                                        }
                                    }
                                }
                            }
                        } else if (TezCompilerUtil.isNonPackageInput(successor.getOperatorKey().toString(), succSuccessor)) {
                            // Output goes to scalar or POFRJoinTez instead of POPackage
                            // POPackage/POShuffleTezLoad can handle multiple inputs from a Split.
                            // But if input is sent to any other operator like
                            // scalar, POFRJoinTez then we need to ensure it is the only one.
                            canMerge = canMerge ? nonPackageInputSuccessors.add(succSuccessor) : false;
                        } else {
                            toMergeSuccessors.add(succSuccessor);
                        }
                    }
                }

                if (canMerge) {
                    if (!nonPackageInputSuccessors.isEmpty() || !mergedNonPackageInputSuccessors.isEmpty()) {
                        // If a non-POPackage input successor is already merged or
                        // if there is a POPackage and non-POPackage to be merged,
                        // then skip as it will become diamond shape
                        // For eg: POFRJoinTez+Scalar, POFRJoinTez/Scalar+POPackage
                        if (nonPackageInputSuccessors.removeAll(mergedSuccessors)
                                || toMergeSuccessors.removeAll(mergedNonPackageInputSuccessors)
                                || toMergeSuccessors.removeAll(nonPackageInputSuccessors)) {
                            continue;
                        }
                    }
                } else {
                    continue;
                }

                mergedSuccessors.retainAll(toNotMergeSuccessors);
                if (mergedSuccessors.isEmpty()) { // no shared edge after merge
                    splittees.add(successor);
                    mergedNonPackageInputSuccessors.addAll(nonPackageInputSuccessors);
                }
            }

            if (splittees.size() == 0) {
                return;
            }

            if (splittees.size()==1 && successors.size()==1) {
                // We don't need a POSplit here, we can merge the splittee into spliter
                PhysicalOperator firstNodeLeaf = tezOp.plan.getLeaves().get(0);
                PhysicalOperator firstNodeLeafPred  = tezOp.plan.getPredecessors(firstNodeLeaf).get(0);

                TezOperator singleSplitee = splittees.get(0);
                PhysicalOperator secondNodeRoot =  singleSplitee.plan.getRoots().get(0);
                PhysicalOperator secondNodeSucc = singleSplitee.plan.getSuccessors(secondNodeRoot).get(0);

                tezOp.plan.remove(firstNodeLeaf);
                singleSplitee.plan.remove(secondNodeRoot);

                tezOp.plan.merge(singleSplitee.plan);
                tezOp.plan.connect(firstNodeLeafPred, secondNodeSucc);

                addSubPlanPropertiesToParent(tezOp, singleSplitee);

                removeSplittee(getPlan(), tezOp, singleSplitee);
            } else {
                POValueOutputTez valueOutput = (POValueOutputTez)tezOp.plan.getLeaves().get(0);
                POSplit split = new POSplit(OperatorKey.genOpKey(valueOutput.getOperatorKey().getScope()));
                split.copyAliasFrom(valueOutput);
                for (TezOperator splitee : splittees) {
                    PhysicalOperator spliteeRoot =  splitee.plan.getRoots().get(0);
                    splitee.plan.remove(spliteeRoot);
                    split.addPlan(splitee.plan);

                    addSubPlanPropertiesToParent(tezOp, splitee);

                    removeSplittee(getPlan(), tezOp, splitee);
                    valueOutput.removeOutputKey(splitee.getOperatorKey().toString());
                }
                if (valueOutput.getTezOutputs().length > 0) {
                    // We still need valueOutput
                    PhysicalPlan phyPlan = new PhysicalPlan();
                    phyPlan.addAsLeaf(valueOutput);
                    split.addPlan(phyPlan);
                }
                PhysicalOperator pred = tezOp.plan.getPredecessors(valueOutput).get(0);
                tezOp.plan.disconnect(pred, valueOutput);
                tezOp.plan.remove(valueOutput);
                tezOp.plan.add(split);
                tezOp.plan.connect(pred, split);
            }
        } catch (PlanException e) {
            throw new VisitorException(e);
        }
    }

    private void removeSplittee(TezOperPlan plan, TezOperator splitter,
            TezOperator splittee) throws PlanException, VisitorException {

        plan.disconnect(splitter, splittee);

        String spliteeKey = splittee.getOperatorKey().toString();
        String splitterKey = splitter.getOperatorKey().toString();

        if (plan.getPredecessors(splittee) != null) {
            for (TezOperator pred : new ArrayList<TezOperator>(plan.getPredecessors(splittee))) {
                List<TezOutput> tezOutputs = PlanHelper.getPhysicalOperators(pred.plan,
                        TezOutput.class);
                for (TezOutput tezOut : tezOutputs) {
                    if (ArrayUtils.contains(tezOut.getTezOutputs(), spliteeKey)) {
                        tezOut.replaceOutput(spliteeKey, splitterKey);
                    }
                }

                TezEdgeDescriptor edge = pred.outEdges.remove(splittee.getOperatorKey());
                if (edge == null) {
                    throw new VisitorException("Edge description is empty");
                }
                pred.outEdges.put(splitter.getOperatorKey(), edge);
                splitter.inEdges.put(pred.getOperatorKey(), edge);
                plan.disconnect(pred, splittee);
                plan.connect(pred, splitter);
            }
        }

        if (plan.getSuccessors(splittee) != null) {
            List<TezOperator> succs = new ArrayList<TezOperator>(plan.getSuccessors(splittee));
            List<TezOperator> splitterSuccs = plan.getSuccessors(splitter);
            for (TezOperator succTezOperator : succs) {
                TezEdgeDescriptor edge = succTezOperator.inEdges.get(splittee.getOperatorKey());
                splitter.outEdges.remove(splittee.getOperatorKey());
                succTezOperator.inEdges.remove(splittee.getOperatorKey());
                plan.disconnect(splittee, succTezOperator);

                // Do not connect again in case of self join/cross/cogroup or union
                if (splitterSuccs == null || !splitterSuccs.contains(succTezOperator)) {
                    TezCompilerUtil.connectNoLRReconnect(plan, splitter, succTezOperator, edge);
                }

                try {
                    List<TezInput> inputs = PlanHelper.getPhysicalOperators(succTezOperator.plan, TezInput.class);
                    for (TezInput input : inputs) {
                        input.replaceInput(spliteeKey,
                                splitterKey);
                    }
                    List<POUserFunc> userFuncs = PlanHelper.getPhysicalOperators(succTezOperator.plan, POUserFunc.class);
                    for (POUserFunc userFunc : userFuncs) {
                        if (userFunc.getFunc() instanceof ReadScalarsTez) {
                            TezInput tezInput = (TezInput)userFunc.getFunc();
                            tezInput.replaceInput(spliteeKey,
                                    splitterKey);
                            userFunc.getFuncSpec().setCtorArgs(tezInput.getTezInputs());
                        }
                    }
                } catch (VisitorException e) {
                    throw new PlanException(e);
                }

                if (succTezOperator.isUnion()) {
                    int index = succTezOperator.getUnionMembers().indexOf(splittee.getOperatorKey());
                    while (index > -1) {
                        succTezOperator.getUnionMembers().set(index, splitter.getOperatorKey());
                        index = succTezOperator.getUnionMembers().indexOf(splittee.getOperatorKey());
                    }
                }
            }
        }
        plan.remove(splittee);
    }

    private void addSubPlanPropertiesToParent(TezOperator parentOper, TezOperator subPlanOper) {
        // Copy only map side properties. For eg: crossKeys.
        // Do not copy reduce side specific properties. For eg: useSecondaryKey, segmentBelow, sortOrder, etc
        if (subPlanOper.getCrossKeys() != null) {
            for (String key : subPlanOper.getCrossKeys()) {
                parentOper.addCrossKey(key);
            }
        }
        parentOper.copyFeatures(subPlanOper, null);

        // For skewed join right input
        if (subPlanOper.getSampleOperator() !=  null) {
            parentOper.setSampleOperator(subPlanOper.getSampleOperator());
        }

        if (subPlanOper.getRequestedParallelism() > parentOper.getRequestedParallelism()) {
            parentOper.setRequestedParallelism(subPlanOper.getRequestedParallelism());
        }
        subPlanOper.setRequestedParallelismByReference(parentOper);
        parentOper.UDFs.addAll(subPlanOper.UDFs);
        parentOper.scalars.addAll(subPlanOper.scalars);
        if (subPlanOper.outEdges != null) {
            for (Entry<OperatorKey, TezEdgeDescriptor> entry: subPlanOper.outEdges.entrySet()) {
                parentOper.outEdges.put(entry.getKey(), entry.getValue());
            }
        }
    }
}
