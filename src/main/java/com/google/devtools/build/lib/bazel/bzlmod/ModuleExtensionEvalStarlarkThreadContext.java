// Copyright 2021 The Bazel Authors. All rights reserved.
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
//

package com.google.devtools.build.lib.bazel.bzlmod;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.packages.NoSuchPackageException;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.RuleFactory.InvalidRuleException;
import com.google.devtools.build.lib.packages.StarlarkNativeModule.ExistingRulesShouldBeNoOp;
import java.util.HashMap;
import java.util.Map;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.syntax.Location;

/**
 * A context object that should be stored in a {@link StarlarkThread} for use during module
 * extension evaluation.
 */
public final class ModuleExtensionEvalStarlarkThreadContext {
  public void storeInThread(StarlarkThread thread) {
    thread.setThreadLocal(ModuleExtensionEvalStarlarkThreadContext.class, this);
    // The following is just a hack; see documentation there for an explanation.
    thread.setThreadLocal(ExistingRulesShouldBeNoOp.class, new ExistingRulesShouldBeNoOp());
  }

  public static ModuleExtensionEvalStarlarkThreadContext from(StarlarkThread thread) {
    return thread.getThreadLocal(ModuleExtensionEvalStarlarkThreadContext.class);
  }

  @AutoValue
  abstract static class RepoSpecAndLocation {
    abstract RepoSpec getRepoSpec();

    abstract Location getLocation();

    static RepoSpecAndLocation create(RepoSpec repoSpec, Location location) {
      return new AutoValue_ModuleExtensionEvalStarlarkThreadContext_RepoSpecAndLocation(
          repoSpec, location);
    }
  }

  private final String repoPrefix;
  private final PackageIdentifier basePackageId;
  private final RepositoryMapping repoMapping;
  private final BlazeDirectories directories;
  private final ExtendedEventHandler eventHandler;
  private final Map<String, RepoSpecAndLocation> generatedRepos = new HashMap<>();

  public ModuleExtensionEvalStarlarkThreadContext(
      String repoPrefix,
      PackageIdentifier basePackageId,
      RepositoryMapping repoMapping,
      BlazeDirectories directories,
      ExtendedEventHandler eventHandler) {
    this.repoPrefix = repoPrefix;
    this.basePackageId = basePackageId;
    this.repoMapping = repoMapping;
    this.directories = directories;
    this.eventHandler = eventHandler;
  }

  public void createRepo(StarlarkThread thread, Dict<String, Object> kwargs, RuleClass ruleClass)
      throws InterruptedException, EvalException {
    Object nameValue = kwargs.getOrDefault("name", Starlark.NONE);
    if (!(nameValue instanceof String name)) {
      throw Starlark.errorf(
          "expected string for attribute 'name', got '%s'", Starlark.type(nameValue));
    }
    RepositoryName.validateUserProvidedRepoName(name);
    RepoSpecAndLocation conflict = generatedRepos.get(name);
    if (conflict != null) {
      throw Starlark.errorf(
          "A repo named %s is already generated by this module extension at %s",
          name, conflict.getLocation());
    }
    String prefixedName = repoPrefix + name;
    try {
      Rule rule =
          BzlmodRepoRuleCreator.createRule(
              basePackageId,
              repoMapping,
              directories,
              thread.getSemantics(),
              eventHandler,
              "RepositoryRuleFunction.createRule",
              ruleClass,
              Maps.transformEntries(kwargs, (k, v) -> k.equals("name") ? prefixedName : v));

      Map<String, Object> attributes =
          Maps.filterKeys(
              Maps.transformEntries(kwargs, (k, v) -> rule.getAttr(k)), k -> !k.equals("name"));
      String bzlFile = ruleClass.getRuleDefinitionEnvironmentLabel().getUnambiguousCanonicalForm();
      RepoSpec repoSpec =
          RepoSpec.builder()
              .setBzlFile(bzlFile)
              .setRuleClassName(ruleClass.getName())
              .setAttributes(AttributeValues.create(attributes))
              .build();

      generatedRepos.put(name, RepoSpecAndLocation.create(repoSpec, thread.getCallerLocation()));
    } catch (InvalidRuleException | NoSuchPackageException e) {
      throw Starlark.errorf("%s", e.getMessage());
    }
  }

  /**
   * Returns the repos generated by the extension so far. The key is the "internal name" (as
   * specified by the extension) of the repo, and the value is the package containing (only) the
   * repo rule.
   */
  public ImmutableMap<String, RepoSpec> getGeneratedRepoSpecs() {
    return ImmutableMap.copyOf(
        Maps.transformValues(generatedRepos, RepoSpecAndLocation::getRepoSpec));
  }
}
