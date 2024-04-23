// Copyright 2024 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.packages;

import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkThread;

/** Definition of the functions used in VENDOR.bazel file. */
public final class VendorGlobals {
  private VendorGlobals() {}

  public static final VendorGlobals INSTANCE = new VendorGlobals();

  @StarlarkMethod(
      name = "ignore",
      doc = "Ignore this repo from vendoring. Bazel will never vendor it or consider this directory"
          + "while building in vendor mode.",
      parameters = {
          @Param(
              name = "repo_name",
              doc =
                  "The name of the external repo representing this dependency. This must be the "
                      + "canonical repo name",
              named = true,
              defaultValue = "''")
      },
      useStarlarkThread = true)
  public Object ignore(String repoName, StarlarkThread thread)
      throws EvalException {
    VendorThreadContext context = VendorThreadContext.fromOrFail(thread, "ignore()");
    context.addIgnoredRepo(getRepositoryName(repoName));
    return Starlark.NONE;
  }

  @StarlarkMethod(
      name = "pin",
      doc = "Pin the contents of this repo under the vendor directory. Bazel will not update this"
          + "repo while vendoring, and will always use it as is when building in vendor mode",
      parameters = {
          @Param(
              name = "repo_name",
              doc =
                  "The name of the external repo representing this dependency. This must be the "
                      + "canonical repo name",
              named = true,
              defaultValue = "''")
      },
      useStarlarkThread = true)
  public Object pin(String repoName, StarlarkThread thread)
      throws EvalException {
    VendorThreadContext context = VendorThreadContext.fromOrFail(thread, "pin()");
    context.addPinnedRepo(getRepositoryName(repoName));
    return Starlark.NONE;
  }

  private RepositoryName getRepositoryName(String repoName) throws EvalException {
    if (repoName.isEmpty()) {
      throw Starlark.errorf("repo_name parameter must be specified");
    }
    if (!repoName.startsWith("@@")) {
      throw Starlark.errorf("repo_name parameter must be a canonical repo name");
    }
    try{
      return RepositoryName.create(repoName.substring(2));
    } catch (LabelSyntaxException e) {
      throw Starlark.errorf("Invalid repo name: %s", e.getMessage());
    }
  }

}
