// Copyright 2023 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.analysis.actions;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;

import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.FileProvider;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.rules.java.JavaCompilationArgsProvider;
import com.google.devtools.build.lib.rules.java.JavaInfo;
import java.io.IOException;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.Starlark;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link StrippingPathMapper}. */
@RunWith(JUnit4.class)
public class StrippingPathMapperTest extends BuildViewTestCase {

  @Before
  public void setUp() throws Exception {
    useConfiguration("--experimental_output_paths=strip");
  }

  @Test
  public void javaLibraryWithJavacopts() throws Exception {
    scratch.file(
        "java/com/google/test/BUILD",
        "genrule(",
        "    name = 'gen_b',",
        "    outs = ['B.java'],",
        "    cmd = '<some command>',",
        ")",
        "genrule(",
        "    name = 'gen_c',",
        "    outs = ['C.java'],",
        "    cmd = '<some command>',",
        ")",
        "java_library(",
        "    name = 'a',",
        "    javacopts = [",
        "        '-XepOpt:foo:bar=$(location B.java)',",
        "        '-XepOpt:baz=$(location C.java),$(location B.java)',",
        "    ],",
        "    srcs = [",
        "        'A.java',",
        "        'B.java',",
        "        'C.java',",
        "    ],",
        ")");

    ConfiguredTarget configuredTarget = getConfiguredTarget("//java/com/google/test:a");
    Artifact compiledArtifact =
        JavaInfo.getProvider(JavaCompilationArgsProvider.class, configuredTarget)
            .getDirectCompileTimeJars()
            .toList()
            .get(0);
    SpawnAction action = (SpawnAction) getGeneratingAction(compiledArtifact);
    Spawn spawn = action.getSpawn(new ActionExecutionContextBuilder().build());

    assertThat(spawn.getPathMapper().isNoop()).isFalse();
    String outDir = analysisMock.getProductName() + "-out";
    assertThat(
            spawn.getArguments().stream()
                .filter(arg -> arg.contains("java/com/google/test/"))
                .collect(toImmutableList()))
        .containsExactly(
            "java/com/google/test/A.java",
            outDir + "/bin/java/com/google/test/B.java",
            outDir + "/bin/java/com/google/test/C.java",
            outDir + "/bin/java/com/google/test/liba-hjar.jar",
            outDir + "/bin/java/com/google/test/liba-hjar.jdeps",
            "-XepOpt:foo:bar=" + outDir + "/bin/java/com/google/test/B.java",
            "-XepOpt:baz="
                + outDir
                + "/bin/java/com/google/test/C.java,"
                + outDir
                + "/bin/java"
                + "/com/google/test/B.java");
  }

  private void addStarlarkRule(Dict<String, String> executionRequirements) throws IOException {
    scratch.file("defs/BUILD");
    scratch.file(
        "defs/defs.bzl",
        "def _my_rule_impl(ctx):",
        "    args = ctx.actions.args()",
        "    args.add(ctx.outputs.out)",
        "    args.add_all(",
        "        depset(ctx.files.srcs),",
        "        before_each = '-source',",
        "        format_each = '<%s>',",
        "    )",
        "    ctx.actions.run(",
        "        outputs = [ctx.outputs.out],",
        "        inputs = ctx.files.srcs,",
        "        executable = ctx.executable._tool,",
        "        arguments = [args],",
        "        mnemonic = 'MyRuleAction',",
        String.format("        execution_requirements = %s,", Starlark.repr(executionRequirements)),
        "    )",
        "    return [DefaultInfo(files = depset([ctx.outputs.out]))]",
        "my_rule = rule(",
        "    implementation = _my_rule_impl,",
        "    attrs = {",
        "        'srcs': attr.label_list(allow_files = True),",
        "        'out': attr.output(mandatory = True),",
        "        '_tool': attr.label(",
        "            default = '//tool',",
        "            executable = True,",
        "            cfg = 'exec',",
        "        ),",
        "    },",
        ")");
    scratch.file(
        "pkg/BUILD",
        "load('//defs:defs.bzl', 'my_rule')",
        "genrule(",
        "    name = 'gen_src',",
        "    outs = ['gen_src.txt'],",
        "    cmd = '<some command>',",
        ")",
        "my_rule(",
        "    name = 'my_rule',",
        "    out = 'out.bin',",
        "    srcs = [",
        "        ':gen_src',",
        "        'source.txt',",
        "    ],",
        ")");
    scratch.file(
        "tool/BUILD",
        "sh_binary(",
        "    name = 'tool',",
        "    srcs = ['tool.sh'],",
        "    visibility = ['//visibility:public'],",
        ")");
  }

  @Test
  public void starlarkRule_optedInViaExecutionRequirements() throws Exception {
    addStarlarkRule(
        Dict.<String, String>builder().put("supports-path-mapping", "1").buildImmutable());

    ConfiguredTarget configuredTarget = getConfiguredTarget("//pkg:my_rule");
    Artifact outputArtifact =
        configuredTarget.getProvider(FileProvider.class).getFilesToBuild().toList().get(0);
    SpawnAction action = (SpawnAction) getGeneratingAction(outputArtifact);
    Spawn spawn = action.getSpawn(new ActionExecutionContextBuilder().build());

    assertThat(spawn.getPathMapper().isNoop()).isFalse();
    String outDir = analysisMock.getProductName() + "-out";
    assertThat(spawn.getArguments().stream().collect(toImmutableList()))
        .containsExactly(
            outDir + "/bin/tool/tool",
            outDir + "/bin/pkg/out.bin",
            "-source",
            "<" + outDir + "/bin/pkg/gen_src.txt>",
            "-source",
            "<pkg/source.txt>")
        .inOrder();
  }

  @Test
  public void starlarkRule_optedInViaModifyExecutionInfo() throws Exception {
    useConfiguration(
        "--experimental_output_paths=strip",
        "--modify_execution_info=MyRuleAction=+supports-path-mapping");
    addStarlarkRule(Dict.empty());

    ConfiguredTarget configuredTarget = getConfiguredTarget("//pkg:my_rule");
    Artifact outputArtifact =
        configuredTarget.getProvider(FileProvider.class).getFilesToBuild().toList().get(0);
    SpawnAction action = (SpawnAction) getGeneratingAction(outputArtifact);
    Spawn spawn = action.getSpawn(new ActionExecutionContextBuilder().build());

    assertThat(spawn.getPathMapper().isNoop()).isFalse();
    String outDir = analysisMock.getProductName() + "-out";
    assertThat(spawn.getArguments().stream().collect(toImmutableList()))
        .containsExactly(
            outDir + "/bin/tool/tool",
            outDir + "/bin/pkg/out.bin",
            "-source",
            "<" + outDir + "/bin/pkg/gen_src.txt>",
            "-source",
            "<pkg/source.txt>")
        .inOrder();
  }
}
