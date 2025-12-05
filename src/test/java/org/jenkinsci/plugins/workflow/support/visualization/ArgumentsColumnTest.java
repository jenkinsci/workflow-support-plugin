/*
 * The MIT License
 *
 * Copyright (c) 2018, Intel Corporation
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jenkinsci.plugins.workflow.support.visualization;

import static org.junit.Assert.*;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.graph.StepNode;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.support.visualization.table.ArgumentsColumn;

public class ArgumentsColumnTest extends Step {

    private static final class DescriptorMock extends StepDescriptor {
        @Override
        public String getFunctionName() {
            return "func";
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return null;
        }

        @Override
        public String argumentsToString(Map<String, Object> namedArgs) {
            return "123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123123";
        }
    }

    private class TestedNode extends FlowNode implements StepNode {
        public TestedNode() {
			super(null, "1", new ArrayList<FlowNode>());
        }

        @Override
        public StepDescriptor getDescriptor() {
			return new DescriptorMock();
        }

        @Override
        protected String getTypeDisplayName() {
            return null;
        }
    }

    @Test
    public void testLongStepArguments() {
        FlowNode f = new TestedNode();
        ArgumentsColumn col = new ArgumentsColumn();

        String s = col.get(f);
        assertTrue(s.length()<=80);
        assertTrue(StringUtils.right(s, 3).equals("..."));
    }


    @Override public StepExecution start(final StepContext context) throws Exception {
		return null;
    }

}
